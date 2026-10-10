#!/usr/bin/env bash
# Dev entrypoint: run Spring Boot and restart when Java sources change.
#
# The host source tree is mounted read-only at /src/apps/api and mirrored into
# /workspace/apps/api, so Maven output (target/) stays inside the container and
# never collides with host builds (make check, IDE) or leaves root-owned files.
set -euo pipefail

SRC=/src/apps/api
WORK=/workspace/apps/api

sync_sources() {
  rsync -a --delete --exclude 'target/' "$SRC/" "$WORK/"
}

mkdir -p "$WORK"
sync_sources
cd "$WORK"

echo "==> AMRAP API (dev) — first start may take a few minutes (Maven download + compile)"

start_api() {
  mvn -pl api-bootstrap -am -DskipTests spring-boot:run
}

while true; do
  start_api &
  api_pid=$!

  # Block until a source change on the host (ignore target/ build output)
  inotifywait -r -e modify,create,delete,move \
    --exclude '(^|/)target(/|$)' \
    "$SRC" \
    2>/dev/null || true

  echo ""
  echo "==> Java change detected — restarting API..."
  kill "$api_pid" 2>/dev/null || true
  # spring-boot:run may leave a child JVM
  pkill -f 'api-bootstrap' 2>/dev/null || true
  wait "$api_pid" 2>/dev/null || true
  sleep 1
  sync_sources
done
