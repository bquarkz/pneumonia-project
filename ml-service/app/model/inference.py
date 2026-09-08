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
_MANIFEST_PATH = Path(__file__).parent / "manifest.yaml"

# Served when no model has been promoted yet (see ml-service/lab/README.md's "Promoting a
# model") — keeps /predict responding with a well-formed result instead of erroring out.
FALLBACK_VERSION = "fallback-0.0.0"


def _preprocess(image_bytes: bytes) -> np.ndarray:
    image = Image.open(io.BytesIO(image_bytes)).convert("RGB").resize((IMG_SIZE, IMG_SIZE))
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

        if self.model_version and _ONNX_PATH.exists():
            # No try/except here on purpose: a manifest that claims a promoted model
            # but fails to load (corrupt file, opset mismatch) SHALL crash startup
            # loudly, not silently degrade to fallback predictions.
            self._session = ort.InferenceSession(str(_ONNX_PATH), providers=["CPUExecutionProvider"])
            logger.info("Loaded model version=%s from %s", self.model_version, _ONNX_PATH)
        else:
            self.model_version = FALLBACK_VERSION
            logger.warning(
                "No promoted model (manifest.model_version or %s missing) — serving fallback predictions",
                _ONNX_PATH,
            )

    def predict(self, image_bytes: bytes) -> tuple[bool, float]:
        if self._session is None:
            return _fallback_predict(image_bytes)

        input_name = self._session.get_inputs()[0].name
        logits = self._session.run(None, {input_name: _preprocess(image_bytes)})[0]
        probability = float(1.0 / (1.0 + np.exp(-logits.ravel()[0])))
        pneumonia = probability > 0.5
        return pneumonia, round(probability if pneumonia else 1 - probability, 4)
