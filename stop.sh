#!/usr/bin/env bash
# Stops the stack. Pass -v to also wipe the postgres/rabbitmq volumes
# (xray-storage is a host bind mount, so it's untouched either way).
set -euo pipefail
cd "$(dirname "$0")"

docker compose down "$@"

echo "Stack is down."
