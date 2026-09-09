#!/usr/bin/env bash
# Brings the stack up in the background and blocks until every service is
# healthy, then prints the URL to open. Plain `docker compose up --build -d`
# returns the prompt immediately but gives no signal that the app is ready.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "No .env found, copying .env.example to .env..."
  cp .env.example .env
fi

MAX_ATTEMPTS=3
attempt=1
until docker compose up --build -d; do
  if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    echo "Failed to build/start the stack after $MAX_ATTEMPTS attempts. This usually means Docker couldn't reach Docker Hub (check your internet connection) rather than a problem with the project itself." >&2
    exit 1
  fi
  echo "Build failed (attempt $attempt/$MAX_ATTEMPTS), likely a transient network issue pulling images. Retrying in 10s..."
  attempt=$((attempt + 1))
  sleep 10
done

SERVICES=(postgres rabbitmq keycloak backend ml-service frontend)
TIMEOUT=600
START=$(date +%s)

echo "Waiting for all services to become healthy..."

for svc in "${SERVICES[@]}"; do
  cid=$(docker compose ps -q "$svc")
  while [ "$(docker inspect -f '{{.State.Health.Status}}' "$cid")" != "healthy" ]; do
    if [ $(( $(date +%s) - START )) -gt "$TIMEOUT" ]; then
      echo "Timed out waiting for '$svc' to become healthy. Check: docker compose logs $svc" >&2
      exit 1
    fi
    sleep 2
  done
done

echo
echo "Stack is up. Open http://localhost:4200 in your browser to test the app."
echo "Login: demo / demo123"
