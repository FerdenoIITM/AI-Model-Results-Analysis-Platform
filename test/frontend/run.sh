#!/usr/bin/env bash
# Frontend tests: type-check and run Vitest (jsdom + MSW) with coverage inside node:20,
# so the host needs only Docker. node_modules is cached outside the repo between runs.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
REPORTS="${REPORTS_DIR:-$ROOT/test/reports}"
CACHE="${AMRAP_NODE_CACHE:-$HOME/.cache/amrap-test/web-node_modules}"
mkdir -p "$REPORTS" "$CACHE"
rm -f "$REPORTS/frontend.json"

docker run --rm \
  -u "$(id -u):$(id -g)" -e HOME=/tmp -e CI=1 \
  -v "$ROOT/apps/web:/app" \
  -v "$CACHE:/app/node_modules" \
  -v "$REPORTS:/reports" \
  -w /app node:20-alpine sh -c '
    set -e
    want="$(sha1sum package-lock.json | cut -d" " -f1)"
    if [ "$(cat node_modules/.lock-sha 2>/dev/null)" != "$want" ]; then
      echo "Installing web dependencies..."
      npm ci --no-audit --no-fund --loglevel=error
      echo "$want" > node_modules/.lock-sha
    fi
    echo "Type-checking (tsc --noEmit)..."
    npx tsc --noEmit
    npx vitest run --coverage "$@" \
      --reporter=default --reporter=json --outputFile.json=/reports/frontend.json
  ' sh "$@"
