#!/usr/bin/env bash
# Runs the AMRAP test suites, prints a pass/fail/skip table with coverage locations,
# and exits non-zero if any suite fails.
#
#   test/run-all.sh                      every suite
#   test/run-all.sh frontend database    only the named suites
#
# Suites: frontend backend integration database api e2e
# The isolated stack (test/docker-compose.test.yml) is started for api/e2e and always
# torn down afterwards; set KEEP_STACK=1 to leave it running for debugging.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export REPORTS_DIR="$ROOT/test/reports"
ALL_SUITES=(frontend backend integration database api e2e)
SUITES=("$@")
[ ${#SUITES[@]} -eq 0 ] && SUITES=("${ALL_SUITES[@]}")

for suite in "${SUITES[@]}"; do
  if [[ ! " ${ALL_SUITES[*]} " =~ " ${suite} " ]]; then
    echo "Unknown suite '$suite'. Choose from: ${ALL_SUITES[*]}" >&2
    exit 2
  fi
done

# Two runs at once would share apps/api/*/target and the test stack, and break each other
# with errors that are not test failures (missing packages, "Error while storing the mojo status").
if command -v flock >/dev/null 2>&1; then
  exec 9>"$ROOT/test/.run-all.lock"
  if ! flock -n 9; then
    echo "Another test run (make check) is already in progress; wait for it to finish." >&2
    exit 1
  fi
fi

rm -rf "$REPORTS_DIR"
mkdir -p "$REPORTS_DIR"
: > "$REPORTS_DIR/status"

wants() { [[ " ${SUITES[*]} " =~ " $1 " ]]; }

STACK_STARTED=0
teardown() {
  if [ "$STACK_STARTED" = 1 ] && [ "${KEEP_STACK:-0}" != 1 ]; then
    echo "Tearing down test stack..."
    "$ROOT/test/stack/stack.sh" down >"$REPORTS_DIR/stack-down.log" 2>&1 || true
  fi
}
trap teardown EXIT
trap 'exit 130' INT TERM

run_suite() {
  local name="$1"; shift
  local log="$REPORTS_DIR/$name.log"
  local started=$SECONDS
  printf '\n==> %-12s running (log: %s)\n' "$name" "${log#"$ROOT/"}"
  "$@" >"$log" 2>&1
  local code=$?
  echo "$name $code $((SECONDS - started))" >>"$REPORTS_DIR/status"
  if [ "$code" -eq 0 ]; then
    printf '==> %-12s PASSED in %ss\n' "$name" "$((SECONDS - started))"
  else
    printf '==> %-12s FAILED (exit %s) in %ss; last lines of the log:\n' "$name" "$code" "$((SECONDS - started))"
    tail -n 40 "$log" | sed 's/^/    /'
  fi
}

mark_failed() {
  echo "$1 1 0" >>"$REPORTS_DIR/status"
  printf '==> %-12s FAILED: %s\n' "$1" "$2"
}

backend_unit() {
  cd "$ROOT/apps/api" && rm -rf ./*/target/surefire-reports && mvn -B -ntp test
}

backend_integration() {
  cd "$ROOT/apps/api" && rm -rf api-bootstrap/target/failsafe-reports &&
    mvn -B -ntp verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false
}

wants frontend && run_suite frontend "$ROOT/test/frontend/run.sh"
wants backend && run_suite backend backend_unit
wants integration && run_suite integration backend_integration
wants database && run_suite database "$ROOT/test/database/run.sh"

if wants api || wants e2e; then
  printf '\n==> %-12s starting isolated stack (project amrap-test-suite)\n' "stack"
  STACK_STARTED=1
  if "$ROOT/test/stack/stack.sh" up >"$REPORTS_DIR/stack-up.log" 2>&1; then
    wants api && run_suite api "$ROOT/test/stack/stack.sh" run npx vitest run --config vitest.config.ts \
      --reporter=default --reporter=json --outputFile.json=/reports/api.json
    wants e2e && run_suite e2e "$ROOT/test/stack/stack.sh" run npx playwright test
  else
    tail -n 30 "$REPORTS_DIR/stack-up.log" | sed 's/^/    /'
    wants api && mark_failed api "test stack did not start (see test/reports/stack-up.log)"
    wants e2e && mark_failed e2e "test stack did not start (see test/reports/stack-up.log)"
  fi
fi

python3 "$ROOT/test/summary.py" "$ROOT"
