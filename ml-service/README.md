# ml-service

Pneumonia prediction service via X-ray (FastAPI). Contract fixed in DEC-0001:
`POST /predict` receives an image (multipart) + `X-Correlation-Id` header, returns
`{"pneumonia": bool, "confidence": float, "model_version": string}`; `GET /health` for the
`docker-compose.yml` healthcheck.

## Two parts, two lifecycles

- **`app/`** — what runs in production/demo, via `docker compose up`. Loads the
  already-trained model (if one has been promoted) and serves it. No training, no Jupyter, no GBs of
  data — just the inference libs (`requirements.txt`).
- **`lab/`** — separate conda environment for notebooks and training (see `lab/README.md`). Never
  goes into the Docker image (`.dockerignore`). This is where the DEC-0001 gap ("ML model
  architecture, deferred to a future decision") gets resolved in practice.

## Current state

No model has been promoted yet — `app/model/manifest.yaml` has `model_version: null`.
The service runs in **placeholder mode**: `POST /predict` responds with a
deterministic pseudo-random prediction (hash of the image), enough to exercise the full
pipeline (upload → RabbitMQ → FastAPI → result) with no diagnostic value whatsoever. This is
expected and satisfies PLN-0001's acceptance criteria even before training exists.

Once a model is trained and promoted (`lab/README.md`, "Promoting a model" section),
`app/model/inference.py` will load it automatically on startup — no code change
is needed beyond placing the `.onnx` file and filling in the manifest.

## Running in isolation (without the rest of the stack)

```bash
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

Or via Docker Compose, from the repository root: `docker compose up ml-service`.
