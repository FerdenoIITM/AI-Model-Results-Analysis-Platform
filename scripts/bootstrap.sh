#!/usr/bin/env bash
# Creates .env.<env> from env/<env>.env.example.
#   scripts/bootstrap.sh dev|test|prod
# - Never overwrites an existing .env.<env>.
# - For dev, values from a legacy .env (single-environment setup) are carried over.
# - Every __GENERATE__ value becomes a random secret.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ENV_NAME="${1:-dev}"
TEMPLATE="env/${ENV_NAME}.env.example"
TARGET=".env.${ENV_NAME}"

if [[ ! -f "$TEMPLATE" ]]; then
  echo "Unknown environment '$ENV_NAME' (expected dev, test or prod)." >&2
  exit 2
fi
if [[ -f "$TARGET" ]]; then
  echo "$TARGET already exists — skipped"
  exit 0
fi

random_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

# Keys that describe the old single-environment layout and must not leak into the new files.
NOT_CARRIED="SPRING_PROFILES_ACTIVE COMPOSE_PROJECT_NAME COMPOSE_PROFILES"

declare -A legacy=()
if [[ "$ENV_NAME" == "dev" && -f .env ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      legacy["${BASH_REMATCH[1]}"]="${BASH_REMATCH[2]}"
    fi
  done < .env
fi

generated=()
carried=()
umask 077
while IFS= read -r line || [[ -n "$line" ]]; do
  if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    if [[ -n "${legacy[$key]+set}" && " $NOT_CARRIED " != *" $key "* ]]; then
      value="${legacy[$key]}"
      carried+=("$key")
    fi
    if [[ "$value" == "__GENERATE__" ]]; then
      value="$(random_secret)"
      generated+=("$key")
    fi
    line="${key}=${value}"
  fi
  printf '%s\n' "$line"
done < "$TEMPLATE" > "$TARGET"

echo "Created $TARGET from $TEMPLATE"
if ((${#carried[@]})); then
  echo "  carried over from .env: ${carried[*]}"
fi
if ((${#generated[@]})); then
  echo "  generated random values: ${generated[*]}"
fi
if grep -q '^POSTGRES_PASSWORD=$' "$TARGET"; then
  echo "  fill in POSTGRES_PASSWORD (password of POSTGRES_USER on POSTGRES_HOST) before starting"
fi
echo "Review $TARGET, then run: make ${ENV_NAME}"
