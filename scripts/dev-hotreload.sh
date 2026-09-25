#!/usr/bin/env bash
# Hot-reload without rebuilding app images.
# - Web: Docker node:20-alpine + Vite HMR (uses image you already have locally)
# - API: host Java + Maven spring-boot:run (restarts when .java files change)
#
# Does not need to pull maven:* from Docker Hub (avoids registry DNS failures).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

WEB_PORT="${WEB_PORT:-5173}"
API_PORT="${API_PORT:-8080}"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

# Host process must talk to Postgres on localhost, not host.docker.internal
export POSTGRES_HOST=localhost
export API_PORT

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing '$1' on the host."
    echo "Install once:"
    echo "  sudo apt-get update && sudo apt-get install -y maven inotify-tools"
    echo "Or:  make deps-dev"
    exit 1
  fi
}

need_cmd java
need_cmd mvn
need_cmd inotifywait

if ! docker image inspect node:20-alpine >/dev/null 2>&1; then
  echo "Docker image node:20-alpine not found locally."
  echo "When network works:  docker pull node:20-alpine"
  exit 1
fi

echo "==> Stopping packaged / previous hot-reload containers (free ports)..."
docker compose down 2>/dev/null || true
docker compose -p amrap-dev -f docker-compose.dev.yml down 2>/dev/null || true
docker rm -f amrap-web-dev 2>/dev/null || true

cleanup() {
  echo ""
  echo "==> Stopping web container..."
  docker rm -f amrap-web-dev 2>/dev/null || true
  # Stop API child if still running
  if [[ -n "${API_PID:-}" ]]; then
    kill "$API_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "==> Starting web (Vite HMR) on http://localhost:${WEB_PORT}"
docker run -d --name amrap-web-dev \
  -p "${WEB_PORT}:5173" \
  -v "${ROOT_DIR}/apps/web:/app" \
  -v amrap_web_node_modules:/app/node_modules \
  -e VITE_PROXY_TARGET="http://host.docker.internal:${API_PORT}" \
  --add-host=host.docker.internal:host-gateway \
  -w /app \
  node:20-alpine \
  sh -c "npm install && npm run dev -- --host 0.0.0.0 --port 5173" >/dev/null

echo "==> Starting API (hot reload) on http://localhost:${API_PORT}"
echo "    First Maven run can take a few minutes. Java edits trigger a restart."
echo "    Web: http://localhost:${WEB_PORT}"
echo ""

cd "${ROOT_DIR}/apps/api"

start_api() {
  # -am builds dependent modules in-reactor; parent POM skips spring-boot:run.
  mvn -pl api-bootstrap -am -DskipTests spring-boot:run
}

while true; do
  start_api &
  API_PID=$!

  # Wait until Java sources change (ignore build output)
  inotifywait -r -e modify,create,delete,move \
    --exclude '(^|/)target(/|$)' \
    "${ROOT_DIR}/apps/api" \
    2>/dev/null || true

  echo ""
  echo "==> Java change detected — restarting API..."
  kill "$API_PID" 2>/dev/null || true
  wait "$API_PID" 2>/dev/null || true
  API_PID=""
  sleep 1
done
