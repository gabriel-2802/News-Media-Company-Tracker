#!/bin/bash
# Creates the analytics service's own database and user on first start of
# an empty Postgres volume (docker-entrypoint-initdb.d). The analytics user
# owns `market` and has no access to the manager's database.
# For an existing volume run the same statements by hand (see README).
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE USER ${MARKET_DB_USER:-analytics} WITH PASSWORD '${MARKET_DB_PASSWORD:-analytics}';
CREATE DATABASE ${MARKET_DB_NAME:-market} OWNER ${MARKET_DB_USER:-analytics};
REVOKE ALL ON DATABASE ${POSTGRES_DB} FROM ${MARKET_DB_USER:-analytics};
-- Postgres grants CONNECT to PUBLIC by default; the manager connects as the owner.
REVOKE CONNECT ON DATABASE ${POSTGRES_DB} FROM PUBLIC;
SQL
