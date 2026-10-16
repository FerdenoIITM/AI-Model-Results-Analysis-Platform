#!/usr/bin/env bash
# Database tests: start a throwaway pgvector container initialised from the real migrations,
# run every tests/*.sql file and count "ok" / "not ok" assertions.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
REPORTS="${REPORTS_DIR:-$ROOT/test/reports}"
NAME="amrap-dbtest-$$"
mkdir -p "$REPORTS"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$NAME" \
  -e POSTGRES_DB=amrap -e POSTGRES_USER=amrap -e POSTGRES_PASSWORD=dbtest \
  -e CHAT_DB_USER=amrap_chat_ro -e CHAT_DB_PASSWORD=dbtest-chat \
  -v "$ROOT/infra/db/migrations:/docker-entrypoint-initdb.d:ro" \
  -v "$HERE:/tests:ro" \
  pgvector/pgvector:pg16 >/dev/null || { echo "Could not start pgvector container"; exit 2; }

for _ in $(seq 1 60); do
  if docker logs "$NAME" 2>&1 | grep -q "PostgreSQL init process complete" \
     && docker exec "$NAME" pg_isready -U amrap -d amrap -h 127.0.0.1 >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
if ! docker exec "$NAME" pg_isready -U amrap -d amrap -h 127.0.0.1 >/dev/null 2>&1; then
  echo "Database did not become ready"; docker logs "$NAME" | tail -20; exit 2
fi
if docker logs "$NAME" 2>&1 | grep -q "ERROR:"; then
  echo "Migrations failed to apply:"; docker logs "$NAME" 2>&1 | grep -A3 "ERROR:"; exit 1
fi

psql_run() { docker exec "$NAME" psql -U amrap -d amrap -h 127.0.0.1 -X -q -v ON_ERROR_STOP=0 "$@" 2>&1; }

psql_run -f /tests/lib/assert.sql >/dev/null

total_pass=0
total_fail=0
failures=""
for file in "$HERE"/tests/*.sql; do
  name="$(basename "$file")"
  out="$(psql_run -f "/tests/tests/$name")"
  pass=$(grep -c "NOTICE:  ok - " <<<"$out" || true)
  fail=$(grep -c "ERROR:" <<<"$out" || true)
  total_pass=$((total_pass + pass))
  total_fail=$((total_fail + fail))
  if [ "$fail" -gt 0 ]; then
    printf '  FAIL %-24s %4d passed, %d failed\n' "$name" "$pass" "$fail"
    failures+="$(grep "ERROR:" <<<"$out" | sed 's/^/    /')"$'\n'
  else
    printf '  ok   %-24s %4d passed\n' "$name" "$pass"
  fi
done

if [ -n "$failures" ]; then
  echo "Failed assertions:"
  printf '%s' "$failures"
fi
echo "$total_pass $total_fail 0" > "$REPORTS/database.summary"
echo "Database tests: $total_pass passed, $total_fail failed"
[ "$total_fail" -eq 0 ]
