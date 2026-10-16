-- V5__chat_readonly.sql
-- Read-only view of results for chat text-to-SQL (no users, no file paths, no hashes).
-- Creates Postgres login amrap_chat_ro (not an AMRAP app user) with a default password.
-- After apply, set the real password to match CHAT_DB_PASSWORD in .env.<env>:
--   CHAT_DB_PASSWORD=... POSTGRES_USER=postgres POSTGRES_DB=AMRAP-Dev \
--     ./infra/db/migrations/V5b__chat_ro_role.sh
-- Apply manually on host DBs after V1/V3/V4 (Flyway is disabled).

CREATE OR REPLACE VIEW v_chat_results AS
SELECT
    id,
    model_name,
    version,
    iteration_id,
    inserted_at,
    category,
    sub_category,
    confidence_accuracy,
    sensitivity,
    specificity,
    f1_score,
    recall,
    precision
FROM results;

COMMENT ON VIEW v_chat_results IS
    'Safe projection of results for chat SQL. Queried only by role amrap_chat_ro.';

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'amrap_chat_ro') THEN
    CREATE ROLE amrap_chat_ro LOGIN PASSWORD 'amrap-chat-ro-change-me';
  END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO amrap_chat_ro;
GRANT SELECT ON v_chat_results TO amrap_chat_ro;
