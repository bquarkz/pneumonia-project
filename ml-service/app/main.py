"""Pneumonia X-ray prediction service.

Serves the model promoted to app/model/artifacts/model.onnx (see app/model/manifest.yaml
and ml-service/lab/README.md's "Promoting a model"). If no model has been promoted yet,
Predictor falls back to a deterministic stand-in so /predict keeps responding — see
app/model/inference.py.
"""

import logging

from fastapi import FastAPI, File, Header, HTTPException, UploadFile

from .model.inference import NotChestXrayError, Predictor

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
    try:
        pneumonia, confidence = predictor.predict(contents)
    except NotChestXrayError as exc:
        logger.warning(
            "correlation_id=%s filename=%s size=%d -> rejected: %s",
            x_correlation_id,
            file.filename,
            len(contents),
            exc,
        )
        raise HTTPException(status_code=422, detail=str(exc)) from exc

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
