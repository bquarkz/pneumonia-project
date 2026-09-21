# Pneumonia X-Ray Detection System

Master's thesis project: upload chest X-rays in batch, have each one processed
asynchronously by a Python ML service through a RabbitMQ pipeline, and watch per-image status
update live in the browser — the whole stack runs locally with a single Docker command.

## Architecture at a glance

- **backend/** — Java, Spring Boot + Spring Modulith (3 modules: `infra`, `users`, `xray`).
  Owns the X-ray request lifecycle as a finite state machine (`RECEIVED → QUEUED → PROCESSING
  → DONE`/`FAILED`/`INVALID`, with `RETRYING` in between), driven by RabbitMQ (Spring Modulith
  Event Externalization to produce, plain `@RabbitListener` to consume), persisted in Postgres,
  and pushed to the browser via Server-Sent Events.
- **frontend/** — Angular (latest), batch upload + live status list (with a "View image" button
  per request), authenticated via Keycloak (`keycloak-angular`).
- **ml-service/** — Python/FastAPI. Serves a fine-tuned ResNet18 model (ROC-AUC 0.9574, Recall
  0.9949, Precision 0.8033 on held-out test data) behind the `/predict` contract, gated by an
  out-of-distribution guardrail (grayscale check + two k-NN embedding-distance checks, one in
  the fine-tuned embedding and one in a separate frozen pretrained embedding) that rejects
  non-chest-X-ray uploads with a 422 before they ever reach the model. See
  [`ml-service/REPORT.md`](ml-service/REPORT.md) for the full build narrative and
  [`ml-service/lab/README.md`](ml-service/lab/README.md) for training/promotion details. Every
  response is clearly labeled as non-diagnostic.
- **keycloak/** — realm import (`realm-export.json`): a public frontend client, a resource-
  server-only backend client, and one static fallback user.
- Everything is wired together by the root `docker-compose.yml`, with healthchecks and
  `depends_on: condition: service_healthy` so services never race each other on boot.

## Quickstart

Prerequisites: Docker + Docker Compose. Nothing else needs to be installed locally to run it.

```bash
cp .env.example .env
# edit .env: set real Postgres/RabbitMQ passwords (any value works locally).

./start.sh
```

This runs `docker compose up --build -d` (background, returns your shell prompt immediately)
and then blocks until every service reports healthy, printing the URL to open once it's
ready. Follow logs at any point with `docker compose logs -f`. Stop everything with
`./stop.sh` (add `-v` to also wipe the postgres/rabbitmq volumes).

Once you see the "Stack is up" message, open **http://localhost:4200** in your browser and
log in with the static fallback user: **`demo` / `demo123`**.

The other services aren't meant to be opened directly — they're listed here for reference
(debugging, admin consoles):

| Service              | URL                                      |
|----------------------|-------------------------------------------|
| Frontend             | http://localhost:4200                     |
| Backend API          | http://localhost:8081                     |
| Keycloak             | http://localhost:8080                     |
| RabbitMQ management  | http://localhost:15672                    |
| ML service           | http://localhost:8000                     |

## Known, deliberately-accepted limitations

- **`sample-data/` ships empty.** No real chest X-ray images are included — see
  [`sample-data/README.md`](sample-data/README.md). Add your own JPEGs there before a demo.
- **SSE status updates only work correctly with exactly one backend replica.** The in-process
  emitter registry is not shared across instances. This is an accepted trade-off, not a bug —
  scaling the backend is explicitly out of scope until after the thesis defense.
- **The out-of-distribution guardrail isn't perfect.** Each of its three checks (grayscale,
  k-NN distance in the fine-tuned embedding, k-NN distance in a separate frozen pretrained
  embedding) is individually calibrated to roughly a 1% false-rejection rate on real chest
  X-rays, but stacking them pushes the combined measured rate to ~2.5%. See
  [`ml-service/REPORT.md`](ml-service/REPORT.md) for the full findings. The prediction itself
  is still non-diagnostic regardless of accuracy — this is a thesis project, not a medical device.
- **The RabbitMQ reconciliation/polling safety-net job is not implemented.** The primary path
  (RabbitMQ + manual ack + durable queues) already covers the common failure mode (a worker
  crashing mid-message); the extra safety net for edge cases is tracked as future work.
- **CI is not set up.** Explicitly deferred, same reasoning as above.

## Development

Each subproject can also be run/tested independently:

- `backend/`: `./gradlew bootRun` (needs Postgres, RabbitMQ, and Keycloak reachable —
  easiest to still bring those three up via `docker compose up postgres rabbitmq keycloak`
  and run the backend on the host against them), `./gradlew test`.
- `frontend/`: `npm start` (serves against `environment.ts` defaults, i.e. a locally-running
  backend/Keycloak), `npm test`.
- `ml-service/`: `pip install -r requirements.txt && uvicorn app.main:app --reload`.
