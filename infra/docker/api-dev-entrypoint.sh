#!/usr/bin/env bash
# Dev entrypoint: run Spring Boot and restart when Java sources change.
set -euo pipefail

cd /workspace/apps/api

echo "==> AMRAP API (dev) — first start may take a few minutes (Maven download + compile)"

start_api() {
  mvn -pl api-bootstrap -am -DskipTests spring-boot:run
}

while true; do
  start_api &
  api_pid=$!

  # Block until a Java source change (ignore target/ build output)
  inotifywait -r -e modify,create,delete,move \
    --exclude '(^|/)target(/|$)' \
    /workspace/apps/api \
    2>/dev/null || true

  echo ""
  echo "==> Java change detected — restarting API..."
  kill "$api_pid" 2>/dev/null || true
  # spring-boot:run may leave a child JVM
  pkill -f 'api-bootstrap' 2>/dev/null || true
  wait "$api_pid" 2>/dev/null || true
  sleep 1
done
