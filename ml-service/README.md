# ml-service

Pneumonia prediction service via X-ray (FastAPI).
`POST /predict` receives an image (multipart) + `X-Correlation-Id` header, returns
`{"pneumonia": bool, "confidence": float, "model_version": string}`; `GET /health` for the
`docker-compose.yml` healthcheck.

## Two parts, two lifecycles

- **`app/`** — what runs in production/demo, via `docker compose up`. Loads the
  already-trained model (if one has been promoted) and serves it. No training, no Jupyter, no GBs of
  data — just the inference libs (`requirements.txt`).
- **`lab/`** — separate conda environment for notebooks and training (see `lab/README.md`). Never
  goes into the Docker image (`.dockerignore`). This is where the model itself gets trained,
  evaluated, and promoted.

## Current state

A fine-tuned ResNet18 is promoted (`app/model/manifest.yaml`: model_version `4`, ROC-AUC 0.9574,
Recall 0.9949, Precision 0.8033 on held-out test data). `POST /predict` runs uploads through an
out-of-distribution guardrail first — a grayscale check plus a k-NN embedding-distance check —
and rejects anything that doesn't look like a chest X-ray with a 422 before it ever reaches the
model. See [`REPORT.md`](REPORT.md) for the full build narrative, including the guardrail's known
gaps (a small false-rejection rate on real X-rays, solid-color images slipping through).

If no model has been promoted yet (`manifest.yaml` has `model_version: null`), the service falls
back to a deterministic pseudo-random prediction (hash of the image) so the full pipeline (upload
→ RabbitMQ → FastAPI → result) can still be exercised end to end with no diagnostic value
whatsoever — see `app/model/inference.py`'s `_fallback_predict`.

Training/promoting a new model is documented in `lab/README.md`'s "Promoting a model" section;
`app/model/inference.py` loads whatever is promoted automatically on startup — no code change
needed beyond placing the `.onnx` file and filling in the manifest.

## Running in isolation (without the rest of the stack)

```bash
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

Or via Docker Compose, from the repository root: `docker compose up ml-service`.
