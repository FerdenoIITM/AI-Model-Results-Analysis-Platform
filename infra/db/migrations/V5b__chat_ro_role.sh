#!/usr/bin/env bash
# Optional: sync amrap_chat_ro password with CHAT_DB_PASSWORD from the env file.
# V5__chat_readonly.sql already creates the role with a default password.
# Run after changing CHAT_DB_PASSWORD (host Postgres or existing Compose volumes):
#   CHAT_DB_PASSWORD='(from .env)' POSTGRES_USER=postgres POSTGRES_DB=AMRAP-Dev \
#     ./infra/db/migrations/V5b__chat_ro_role.sh
# On a brand-new Compose db volume this script also runs from docker-entrypoint-initdb.d
# when CHAT_DB_PASSWORD is set on the db service.
set -euo pipefail

CHAT_USER="${CHAT_DB_USER:-amrap_chat_ro}"
CHAT_PASS="${CHAT_DB_PASSWORD:-}"
if [[ -z "$CHAT_PASS" ]]; then
  echo "CHAT_DB_PASSWORD empty — leaving role $CHAT_USER password unchanged (V5 default)."
  exit 0
fi
if [[ ! "$CHAT_USER" =~ ^[a-z_][a-z0-9_]*$ ]]; then
  echo "CHAT_DB_USER must be a simple lowercase identifier (got '$CHAT_USER')" >&2
  exit 2
fi

CHAT_PASS_SQL="${CHAT_PASS//\'/\'\'}"
PGUSER="${POSTGRES_USER:-postgres}"
PGDATABASE="${POSTGRES_DB:-postgres}"

psql -v ON_ERROR_STOP=1 --username "$PGUSER" --dbname "$PGDATABASE" <<EOSQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${CHAT_USER}') THEN
    CREATE ROLE ${CHAT_USER} LOGIN PASSWORD '${CHAT_PASS_SQL}';
  ELSE
    ALTER ROLE ${CHAT_USER} WITH LOGIN PASSWORD '${CHAT_PASS_SQL}';
  END IF;
END
\$\$;

GRANT USAGE ON SCHEMA public TO ${CHAT_USER};
GRANT SELECT ON v_chat_results TO ${CHAT_USER};
EOSQL

echo "Chat read-only role ${CHAT_USER} password synced on ${PGDATABASE}."
