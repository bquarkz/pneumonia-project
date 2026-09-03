"""Pneumonia X-ray prediction service.

Serves whatever model is promoted to app/model/artifacts/model.onnx (see
app/model/manifest.yaml). Until a real model is promoted, Predictor falls back to a
deterministic pseudo-random stand-in so the pipeline is fully exercisable end-to-end
(see DEC-0001's known gap on the ML model architecture, and ml-service/lab/ for training).
"""

import logging

from fastapi import FastAPI, File, Header, UploadFile

from .model.inference import Predictor

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ml-service")

app = FastAPI(title="Pneumonia X-ray ML Service")
predictor = Predictor()


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/predict")
async def predict(
    file: UploadFile = File(...),
    x_correlation_id: str | None = Header(default=None, alias="X-Correlation-Id"),
) -> dict:
    contents = await file.read()
    pneumonia, confidence = predictor.predict(contents)

    logger.info(
        "correlation_id=%s filename=%s size=%d -> pneumonia=%s confidence=%s model_version=%s",
        x_correlation_id,
        file.filename,
        len(contents),
        pneumonia,
        confidence,
        predictor.model_version,
    )

    return {
        "pneumonia": pneumonia,
        "confidence": confidence,
        "model_version": predictor.model_version,
    }
