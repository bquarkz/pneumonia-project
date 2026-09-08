import hashlib
import io
import logging
from pathlib import Path

import numpy as np
import onnxruntime as ort
import yaml
from PIL import Image

logger = logging.getLogger("ml-service")

# Preprocessing constants — MUST stay in sync with the copy in lab/src/dataset.py.
# Duplicated (not imported) because lab/ (conda env) and this runtime image never
# share a Python path.
IMG_SIZE = 224
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)

_ARTIFACTS_DIR = Path(__file__).parent / "artifacts"
_ONNX_PATH = _ARTIFACTS_DIR / "model.onnx"
_EMBEDDINGS_PATH = _ARTIFACTS_DIR / "train_embeddings.npy"
_OOD_ONNX_PATH = _ARTIFACTS_DIR / "ood_embedding.onnx"
_OOD_EMBEDDINGS_PATH = _ARTIFACTS_DIR / "train_embeddings_pretrained.npy"
_MANIFEST_PATH = Path(__file__).parent / "manifest.yaml"

# Served when no model has been promoted yet (see ml-service/lab/README.md's "Promoting a
# model") — keeps /predict responding with a well-formed result instead of erroring out.
FALLBACK_VERSION = "fallback-0.0.0"

# Out-of-distribution guardrail functions — MUST stay in sync with the copy in
# lab/src/ood.py. Duplicated (not imported) because lab/ (conda env) and this runtime
# image never share a Python path. Confidence alone doesn't flag OOD inputs: without
# these, random noise/solid colors turn into a 93-99% confident "pneumonia" prediction,
# and a real abdomen/pelvis X-ray (wrong body part) turns into one too (see
# lab/src/ood.py's docstring). Three checks run in predict(): grayscale, then k-NN
# distance in the fine-tuned classifier's own embedding, then k-NN distance again in a
# separate, frozen ImageNet-pretrained embedding (catches the wrong-body-part case the
# fine-tuned embedding can't). Unlike the functions, the actual threshold values are
# NOT hardcoded here — Predictor reads them from manifest.yaml's `ood` section,
# published by promote.py from lab/artifacts/ood_config.json (see lab/src/ood.py's
# config_dict()), so the model and its OOD config move together as one contract.


class NotChestXrayError(ValueError):
    """Raised when an uploaded image fails the out-of-distribution guardrail."""


def _is_grayscale_like(image: Image.Image, max_channel_std: float, min_grayscale_fraction: float) -> bool:
    array = np.asarray(image.convert("RGB"), dtype=np.float32)
    per_pixel_channel_std = array.std(axis=2)
    grayscale_fraction = (per_pixel_channel_std <= max_channel_std).mean()
    return bool(grayscale_fraction >= min_grayscale_fraction)


def _reference_stats(reference_embeddings: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    mean = reference_embeddings.mean(axis=0)
    std = reference_embeddings.std(axis=0) + 1e-6
    return mean, std


def _standardize(embeddings: np.ndarray, mean: np.ndarray, std: np.ndarray) -> np.ndarray:
    return (embeddings - mean) / std


def _knn_distance(
    query_embedding: np.ndarray,
    reference_embeddings_standardized: np.ndarray,
    mean: np.ndarray,
    std: np.ndarray,
    k: int,
) -> float:
    query_standardized = _standardize(query_embedding, mean, std)
    distances = np.linalg.norm(reference_embeddings_standardized - query_standardized[None, :], axis=1)
    distances.sort()
    return float(distances[:k].mean())


def _preprocess(image: Image.Image) -> np.ndarray:
    image = image.resize((IMG_SIZE, IMG_SIZE))
    array = (np.asarray(image, dtype=np.float32) / 255.0 - MEAN) / STD
    return array.transpose(2, 0, 1)[np.newaxis, ...]  # NCHW, batch of 1


def _fallback_predict(image_bytes: bytes) -> tuple[bool, float]:
    # Deterministic pseudo-randomness derived from the image bytes: repeated calls on
    # the same file always return the same result. Carries no diagnostic value.
    digest = hashlib.sha256(image_bytes).hexdigest()
    score = int(digest[:8], 16) / 0xFFFFFFFF
    pneumonia = score > 0.5
    return pneumonia, round(score if pneumonia else 1 - score, 4)


class Predictor:
    def __init__(self):
        manifest = {}
        if _MANIFEST_PATH.exists():
            manifest = yaml.safe_load(_MANIFEST_PATH.read_text()) or {}

        self.model_version = manifest.get("model_version")
        self._session = None
        self._ood_config = None
        self._ref_mean = None
        self._ref_std = None
        self._ref_standardized = None
        self._ood_session = None
        self._ood_ref_mean = None
        self._ood_ref_std = None
        self._ood_ref_standardized = None

        if self.model_version and _ONNX_PATH.exists():
            # No try/except here on purpose: a manifest that claims a promoted model
            # but fails to load (corrupt file, opset mismatch, missing embeddings/`ood`
            # config) SHALL crash startup loudly, not silently degrade to fallback
            # predictions.
            self._session = ort.InferenceSession(str(_ONNX_PATH), providers=["CPUExecutionProvider"])
            logger.info("Loaded model version=%s from %s", self.model_version, _ONNX_PATH)

            self._ood_config = manifest["ood"]

            reference_embeddings = np.load(_EMBEDDINGS_PATH)
            self._ref_mean, self._ref_std = _reference_stats(reference_embeddings)
            self._ref_standardized = _standardize(reference_embeddings, self._ref_mean, self._ref_std)
            logger.info(
                "Loaded %d reference embeddings for k-NN OOD check from %s (config: %s)",
                reference_embeddings.shape[0],
                _EMBEDDINGS_PATH,
                self._ood_config,
            )

            # Second, independent OOD check: a frozen ImageNet-pretrained (not
            # fine-tuned) ResNet18's embedding, which catches a wrong-anatomical-region
            # X-ray (e.g. abdomen/pelvis) the fine-tuned classifier's embedding above
            # cannot -- it was never trained to preserve body-region signal. See
            # lab/src/ood.py's module docstring and 04_dl_cv_transfer_learning.ipynb's
            # OOD section for how this was found and calibrated.
            self._ood_session = ort.InferenceSession(str(_OOD_ONNX_PATH), providers=["CPUExecutionProvider"])
            ood_reference_embeddings = np.load(_OOD_EMBEDDINGS_PATH)
            self._ood_ref_mean, self._ood_ref_std = _reference_stats(ood_reference_embeddings)
            self._ood_ref_standardized = _standardize(
                ood_reference_embeddings, self._ood_ref_mean, self._ood_ref_std
            )
            logger.info(
                "Loaded %d pretrained-embedding reference embeddings for anatomical-region OOD "
                "check from %s",
                ood_reference_embeddings.shape[0],
                _OOD_EMBEDDINGS_PATH,
            )
        else:
            self.model_version = FALLBACK_VERSION
            logger.warning(
                "No promoted model (manifest.model_version or %s missing) — serving fallback predictions",
                _ONNX_PATH,
            )

    def predict(self, image_bytes: bytes) -> tuple[bool, float]:
        if self._session is None:
            return _fallback_predict(image_bytes)

        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        if not _is_grayscale_like(
            image, self._ood_config["max_channel_std"], self._ood_config["min_grayscale_fraction"]
        ):
            raise NotChestXrayError(
                "Uploaded image does not look like a chest X-ray (expected a grayscale scan)."
            )

        preprocessed = _preprocess(image)
        k = self._ood_config["k_neighbors"]

        input_name = self._session.get_inputs()[0].name
        logits, embedding = self._session.run(["logits", "embedding"], {input_name: preprocessed})

        threshold = self._ood_config["knn_distance_threshold"]
        distance = _knn_distance(embedding[0], self._ref_standardized, self._ref_mean, self._ref_std, k=k)
        if distance > threshold:
            raise NotChestXrayError(
                f"Uploaded image does not look like a chest X-ray (embedding distance "
                f"{distance:.1f} exceeds the {threshold:.1f} threshold)."
            )

        ood_input_name = self._ood_session.get_inputs()[0].name
        (ood_embedding,) = self._ood_session.run(["embedding"], {ood_input_name: preprocessed})

        pretrained_threshold = self._ood_config["knn_distance_threshold_pretrained"]
        pretrained_distance = _knn_distance(
            ood_embedding[0], self._ood_ref_standardized, self._ood_ref_mean, self._ood_ref_std, k=k
        )
        if pretrained_distance > pretrained_threshold:
            raise NotChestXrayError(
                f"Uploaded image does not look like a chest X-ray (anatomical-region embedding "
                f"distance {pretrained_distance:.1f} exceeds the {pretrained_threshold:.1f} threshold)."
            )

        probability = float(1.0 / (1.0 + np.exp(-logits.ravel()[0])))
        pneumonia = probability > 0.5
        return pneumonia, round(probability if pneumonia else 1 - probability, 4)
