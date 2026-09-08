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
_MANIFEST_PATH = Path(__file__).parent / "manifest.yaml"

# Served when no model has been promoted yet (see ml-service/lab/README.md's "Promoting a
# model") — keeps /predict responding with a well-formed result instead of erroring out.
FALLBACK_VERSION = "fallback-0.0.0"

# Out-of-distribution guardrail — MUST stay in sync with the copy in lab/src/ood.py.
# Duplicated (not imported) because lab/ (conda env) and this runtime image never share a
# Python path. Confidence alone doesn't flag OOD inputs: without this, random noise/solid
# colors turn into a 93-99% confident "pneumonia" prediction (see lab/src/ood.py's docstring).
MAX_CHANNEL_STD = 2.0
MIN_GRAYSCALE_FRACTION = 0.95
K_NEIGHBORS = 10
# Calibrated in 04_dl_cv_transfer_learning.ipynb (p99 of leave-one-out k-NN distance among
# training embeddings) — see lab/src/ood.py for the full calibration numbers and two known,
# accepted gaps (a solid achromatic image, and a ~1% real-X-ray false-rejection rate).
KNN_DISTANCE_THRESHOLD = 17.7


class NotChestXrayError(ValueError):
    """Raised when an uploaded image fails the out-of-distribution guardrail."""


def _is_grayscale_like(image: Image.Image) -> bool:
    array = np.asarray(image.convert("RGB"), dtype=np.float32)
    per_pixel_channel_std = array.std(axis=2)
    grayscale_fraction = (per_pixel_channel_std <= MAX_CHANNEL_STD).mean()
    return bool(grayscale_fraction >= MIN_GRAYSCALE_FRACTION)


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
    k: int = K_NEIGHBORS,
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
        self._ref_mean = None
        self._ref_std = None
        self._ref_standardized = None

        if self.model_version and _ONNX_PATH.exists():
            # No try/except here on purpose: a manifest that claims a promoted model
            # but fails to load (corrupt file, opset mismatch, missing embeddings) SHALL
            # crash startup loudly, not silently degrade to fallback predictions.
            self._session = ort.InferenceSession(str(_ONNX_PATH), providers=["CPUExecutionProvider"])
            logger.info("Loaded model version=%s from %s", self.model_version, _ONNX_PATH)

            reference_embeddings = np.load(_EMBEDDINGS_PATH)
            self._ref_mean, self._ref_std = _reference_stats(reference_embeddings)
            self._ref_standardized = _standardize(reference_embeddings, self._ref_mean, self._ref_std)
            logger.info(
                "Loaded %d reference embeddings for k-NN OOD check from %s",
                reference_embeddings.shape[0],
                _EMBEDDINGS_PATH,
            )
        else:
            self.model_version = FALLBACK_VERSION
            logger.warning(
                "No promoted model (manifest.model_version or %s missing) — serving fallback predictions",
                _ONNX_PATH,
            )

    def predict(self, image_bytes: bytes) -> tuple[bool, float]:
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        if not _is_grayscale_like(image):
            raise NotChestXrayError(
                "Uploaded image does not look like a chest X-ray (expected a grayscale scan)."
            )

        if self._session is None:
            return _fallback_predict(image_bytes)

        input_name = self._session.get_inputs()[0].name
        logits, embedding = self._session.run(["logits", "embedding"], {input_name: _preprocess(image)})

        distance = _knn_distance(embedding[0], self._ref_standardized, self._ref_mean, self._ref_std)
        if distance > KNN_DISTANCE_THRESHOLD:
            raise NotChestXrayError(
                f"Uploaded image does not look like a chest X-ray (embedding distance "
                f"{distance:.1f} exceeds the {KNN_DISTANCE_THRESHOLD:.1f} threshold)."
            )

        probability = float(1.0 / (1.0 + np.exp(-logits.ravel()[0])))
        pneumonia = probability > 0.5
        return pneumonia, round(probability if pneumonia else 1 - probability, 4)
