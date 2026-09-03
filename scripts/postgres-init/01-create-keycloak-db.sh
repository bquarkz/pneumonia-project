#!/usr/bin/env bash
# Runs once, automatically, only when the postgres-data volume is freshly
# initialized (the official postgres image only executes
# /docker-entrypoint-initdb.d/ scripts against an empty data directory).
# Gives Keycloak its own database on the same Postgres instance the backend
# uses, instead of the ephemeral H2 start-dev falls back to when KC_DB isn't
# set - the app's Flyway-managed schema and Keycloak's Liquibase-managed
# schema never belong in the same database.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
	CREATE USER ${KEYCLOAK_DB_USER} WITH PASSWORD '${KEYCLOAK_DB_PASSWORD}';
	CREATE DATABASE ${KEYCLOAK_DB} OWNER ${KEYCLOAK_DB_USER};
EOSQL
