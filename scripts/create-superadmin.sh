#!/usr/bin/env bash
# Creates the first superadmin of an environment from SUPERADMIN_* in .env.<env>.
#   scripts/create-superadmin.sh dev|test|prod      (or: make superadmin ENV=<env>)
# Idempotent: an existing account with the same email is left unchanged.
# The password is hashed inside Postgres with pgcrypto (bcrypt, cost 12) and never appears
# on a command line; it is passed to psql through an environment variable.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ENV_NAME="${1:-dev}"
ENV_FILE=".env.${ENV_NAME}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "$ENV_FILE not found. Create it with: make bootstrap ENV=${ENV_NAME}" >&2
  exit 2
fi
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${SUPERADMIN_EMAIL:?set SUPERADMIN_EMAIL in $ENV_FILE}"
: "${SUPERADMIN_PASSWORD:?set SUPERADMIN_PASSWORD in $ENV_FILE}"
SUPERADMIN_NAME="${SUPERADMIN_NAME:-Superadmin}"
SUPERADMIN_EMAIL="$(printf '%s' "$SUPERADMIN_EMAIL" | tr '[:upper:]' '[:lower:]')"
if ((${#SUPERADMIN_PASSWORD} < 8)); then
  echo "SUPERADMIN_PASSWORD must be at least 8 characters" >&2
  exit 2
fi

SQL=$(cat <<'SQL'
\set ON_ERROR_STOP on
\getenv sa_email SUPERADMIN_EMAIL
\getenv sa_name SUPERADMIN_NAME
\getenv sa_password SUPERADMIN_PASSWORD
SET client_min_messages = warning;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO users (email, password_hash, role, display_name, created_by, is_active, password_changed_at)
VALUES (:'sa_email', crypt(:'sa_password', gen_salt('bf', 12)), 'superadmin', :'sa_name', NULL, TRUE, NOW())
ON CONFLICT (email) DO NOTHING
RETURNING 'created' AS status;
SQL
)

export SUPERADMIN_EMAIL SUPERADMIN_NAME PGPASSWORD="$POSTGRES_PASSWORD"
PSQL_ENV=(-e SUPERADMIN_EMAIL -e SUPERADMIN_NAME -e SUPERADMIN_PASSWORD -e PGPASSWORD)
COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f "docker-compose.${ENV_NAME}.yml")

if [[ ",${COMPOSE_PROFILES:-}," == *",db,"* ]]; then
  # Dedicated database container of this environment.
  output=$(printf '%s\n' "$SQL" | "${COMPOSE[@]}" exec -T "${PSQL_ENV[@]}" db \
    psql -X -q -t -A -U "$POSTGRES_USER" -d "$POSTGRES_DB")
else
  # External database (for example host Postgres in dev): use a throwaway psql client.
  output=$(printf '%s\n' "$SQL" | docker run --rm -i "${PSQL_ENV[@]}" \
    --add-host=host.docker.internal:host-gateway \
    "${POSTGRES_IMAGE:-pgvector/pgvector:pg16}" \
    psql -X -q -t -A -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB")
fi

if [[ "$output" == *created* ]]; then
  echo "[$ENV_NAME] superadmin $SUPERADMIN_EMAIL created (password: SUPERADMIN_PASSWORD in $ENV_FILE)"
else
  echo "[$ENV_NAME] superadmin $SUPERADMIN_EMAIL already exists — unchanged"
fi
