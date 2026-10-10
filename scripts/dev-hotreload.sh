#!/usr/bin/env bash
# Dev hot reload with the API on the host (`make dev-local`).
# - Web: node:20-alpine container + Vite HMR, proxying /api to the host API
# - API: host Java + Maven spring-boot:run (restarts when .java files change)
# - Ollama: the dev compose project's ollama service
# Settings come from .env.dev; only the addresses that differ on the host are adjusted.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ENV_FILE=".env.dev"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "$ENV_FILE not found. Create it with: make bootstrap ENV=dev" >&2
  exit 2
fi
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f docker-compose.dev.yml)
WEB_CONTAINER="${COMPOSE_PROJECT_NAME}-web-local"
NODE_IMAGE="${NODE_IMAGE:-node:20-alpine}"

# The API runs on the host, so container-network names become host addresses.
export POSTGRES_HOST="${DEV_LOCAL_DB_HOST:-localhost}"
export OLLAMA_BASE_URL="http://localhost:${OLLAMA_PORT}"
export API_PORT
if [[ "$RESULTS_VOLUME" == ./* || "$RESULTS_VOLUME" == /* ]]; then
  export RESULTS_STORAGE_DIR="$(cd "$ROOT_DIR" && mkdir -p "$RESULTS_VOLUME" && cd "$RESULTS_VOLUME" && pwd)"
fi

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

if ! docker image inspect "$NODE_IMAGE" >/dev/null 2>&1; then
  echo "Docker image $NODE_IMAGE not found locally."
  echo "When network works:  docker pull $NODE_IMAGE"
  exit 1
fi

echo "==> Freeing dev ports (api/web containers of ${COMPOSE_PROJECT_NAME})..."
"${COMPOSE[@]}" rm -sf api web >/dev/null 2>&1 || true
docker rm -f "$WEB_CONTAINER" >/dev/null 2>&1 || true

echo "==> Starting Ollama (${COMPOSE_PROJECT_NAME})..."
"${COMPOSE[@]}" up -d ollama ollama-init >/dev/null

cleanup() {
  echo ""
  echo "==> Stopping web container..."
  docker rm -f "$WEB_CONTAINER" 2>/dev/null || true
  if [[ -n "${API_PID:-}" ]]; then
    kill "$API_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "==> Starting web (Vite HMR) on http://localhost:${WEB_PORT}"
docker run -d --name "$WEB_CONTAINER" \
  -p "${WEB_PORT}:5173" \
  -v "${ROOT_DIR}/apps/web:/app" \
  -v "${COMPOSE_PROJECT_NAME}_web_node_modules:/app/node_modules" \
  -e VITE_PROXY_TARGET="http://host.docker.internal:${API_PORT}" \
  --add-host=host.docker.internal:host-gateway \
  -w /app \
  "$NODE_IMAGE" \
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
