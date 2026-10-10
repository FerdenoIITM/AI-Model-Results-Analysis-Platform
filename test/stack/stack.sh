#!/usr/bin/env bash
# Controls the isolated test stack and runs Node-based suites on its network.
#   stack.sh up            build the API jar and web image, start the stack, wait until healthy
#   stack.sh down          stop the stack and discard its data
#   stack.sh run <cmd...>  run a command in the Playwright image (Node + browsers) on the stack network
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/test/docker-compose.test.yml")
RUNNER_IMAGE="mcr.microsoft.com/playwright:v1.49.1-noble"
CACHE="${AMRAP_NODE_CACHE_DIR:-$HOME/.cache/amrap-test}/system-node_modules"
REPORTS="${REPORTS_DIR:-$ROOT/test/reports}"

case "${1:-}" in
  up)
    echo "Building API jar..."
    (cd "$ROOT/apps/api" && mvn -q -B -pl api-bootstrap -am package -DskipTests)
    echo "Starting test stack (project amrap-test-suite)..."
    "${COMPOSE[@]}" up -d --build --wait --quiet-pull
    "${COMPOSE[@]}" ps --format '  {{.Service}}: {{.State}} {{.Status}}'
    ;;
  down)
    "${COMPOSE[@]}" down -v --remove-orphans
    ;;
  run)
    shift
    mkdir -p "$CACHE" "$REPORTS"
    docker run --rm --init --ipc=host \
      -u "$(id -u):$(id -g)" -e HOME=/tmp -e CI=1 \
      --network amrap-test-suite_default \
      -v "$ROOT/test:/work/test" \
      -v "$CACHE:/work/test/node_modules" \
      -v "$REPORTS:/reports" \
      -w /work/test "$RUNNER_IMAGE" bash -c '
        set -e
        want="$(sha1sum package-lock.json 2>/dev/null | cut -d" " -f1)"
        if [ -z "$want" ] || [ "$(cat node_modules/.lock-sha 2>/dev/null)" != "$want" ]; then
          echo "Installing system test dependencies..."
          if [ -f package-lock.json ]; then npm ci --no-audit --no-fund --loglevel=error; else npm install --no-audit --no-fund --loglevel=error; fi
          sha1sum package-lock.json | cut -d" " -f1 > node_modules/.lock-sha
        fi
        exec "$@"
      ' bash "$@"
    ;;
  *)
    echo "usage: $0 up|down|run <cmd...>" >&2
    exit 2
    ;;
esac
