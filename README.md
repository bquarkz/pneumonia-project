# Pneumonia X-Ray Detection System

Master's thesis project: upload chest X-rays in batch, have each one processed
asynchronously by a Python ML service through a RabbitMQ pipeline, and watch per-image status
update live in the browser — the whole stack runs locally with a single Docker command.

Full architecture rationale lives in `.claude/discussion/decisions/DEC-0001-xray-detection-architecture.md`
and the execution blueprint in `.claude/discussion/plans/PLN-0001-xray-detection-system.md`.

## Architecture at a glance

- **backend/** — Java, Spring Boot + Spring Modulith (3 modules: `infra`, `users`, `xray`).
  Owns the X-ray request lifecycle as a finite state machine (`RECEIVED → QUEUED → PROCESSING
  → DONE`/`FAILED`, with `RETRYING` in between), driven by RabbitMQ (Spring Modulith Event
  Externalization to produce, plain `@RabbitListener` to consume), persisted in Postgres, and
  pushed to the browser via Server-Sent Events.
- **frontend/** — Angular (latest), batch upload + live status list, authenticated via
  Keycloak (`keycloak-angular`).
- **ml-service/** — Python/FastAPI. Exposes the final `/predict` contract behind a
  **placeholder** prediction (no real trained model yet — that's a separate, not-yet-made
  decision). Every response is clearly labeled as non-diagnostic.
- **keycloak/** — realm import (`realm-export.json`): a public frontend client, a resource-
  server-only backend client, and one static fallback user. Google login is layered on
  afterward via a short manual walkthrough (see below) — it cannot be baked into the realm
  import reliably.
- Everything is wired together by the root `docker-compose.yml`, with healthchecks and
  `depends_on: condition: service_healthy` so services never race each other on boot.

## Quickstart

Prerequisites: Docker + Docker Compose. Nothing else needs to be installed locally to run it.

```bash
cp .env.example .env
# edit .env: set real Postgres/RabbitMQ passwords (any value works locally).
# leave GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET empty for now — see below.

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

## Enabling "Login with Google"

Google is an *additional* login option on top of the static fallback user, not a replacement.
It requires a one-time manual setup in Google Cloud Console (can't be automated into
`docker compose up` — it's an external account you control). Full walkthrough:
[`scripts/configure-google-idp.md`](scripts/configure-google-idp.md).

## Known, deliberately-accepted limitations

- **`sample-data/` ships empty.** No real chest X-ray images are included — see
  [`sample-data/README.md`](sample-data/README.md). Add your own JPEGs there before a demo.
- **SSE status updates only work correctly with exactly one backend replica.** The in-process
  emitter registry is not shared across instances. This is an accepted trade-off, not a bug —
  scaling the backend is explicitly out of scope until after the thesis defense.
- **The ML prediction is a placeholder**, not a real diagnosis, until the model-architecture
  decision is made and implemented separately. It is deterministic (same image → same
  placeholder result) but carries no medical meaning.
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
