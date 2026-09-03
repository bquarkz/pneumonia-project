#!/usr/bin/env bash
# Brings the stack up in the background and blocks until every service is
# healthy, then prints the URL to open. Plain `docker compose up --build -d`
# returns the prompt immediately but gives no signal that the app is ready.
set -euo pipefail
cd "$(dirname "$0")"

docker compose up --build -d

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
