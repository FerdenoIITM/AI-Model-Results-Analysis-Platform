#!/usr/bin/env bash
# Install host dependencies needed to run AMRAP (Docker Compose primary path).
# Usage:
#   ./scripts/install-deps.sh              # Docker + .env bootstrap
#   ./scripts/install-deps.sh --with-dev   # also Java 21, Maven, Node 20 (local non-Docker runs)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

WITH_DEV=0
for arg in "$@"; do
  case "$arg" in
    --with-dev) WITH_DEV=1 ;;
    -h|--help)
      echo "Usage: $0 [--with-dev]"
      echo "  Default: install Docker Engine + Compose plugin, ensure .env exists"
      echo "  --with-dev: also install OpenJDK 21, Maven, Node.js 20 (for local API/web)"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 1
      ;;
  esac
done

if [[ "$(id -u)" -eq 0 ]]; then
  SUDO=""
else
  if ! command -v sudo >/dev/null 2>&1; then
    echo "This script needs root privileges (sudo)." >&2
    exit 1
  fi
  SUDO="sudo"
fi

echo "==> Updating apt package index"
$SUDO apt-get update -y

echo "==> Installing Docker Engine + Compose plugin"
$SUDO apt-get install -y ca-certificates curl gnupg
$SUDO apt-get install -y docker.io docker-compose-v2

if ! $SUDO systemctl is-active --quiet docker 2>/dev/null; then
  $SUDO systemctl enable --now docker || true
fi

CURRENT_USER="${SUDO_USER:-$USER}"
if [[ "$CURRENT_USER" != "root" ]]; then
  echo "==> Adding user '$CURRENT_USER' to the docker group"
  $SUDO usermod -aG docker "$CURRENT_USER" || true
fi

if [[ "$WITH_DEV" -eq 1 ]]; then
  echo "==> Installing Java 21 + Maven (local API development)"
  $SUDO apt-get install -y openjdk-21-jdk maven

  echo "==> Installing Node.js 20 (local web development)"
  if ! command -v node >/dev/null 2>&1 || [[ "$(node -v 2>/dev/null | sed 's/v//' | cut -d. -f1 || echo 0)" -lt 20 ]]; then
    curl -fsSL https://deb.nodesource.com/setup_20.x | $SUDO bash -
    $SUDO apt-get install -y nodejs
  else
    echo "    Node $(node -v) already present — skipped NodeSource setup"
  fi
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "==> Created .env from .env.example — edit POSTGRES_* and JWT_SECRET before make up"
else
  echo "==> .env already exists — skipped"
fi

echo
echo "Done."
echo "  Docker:  $(command -v docker >/dev/null && docker --version || echo 'installed; re-login may be required')"
if [[ "$WITH_DEV" -eq 1 ]]; then
  echo "  Java:    $(command -v java >/dev/null && java -version 2>&1 | head -1 || echo 'check install')"
  echo "  Maven:   $(command -v mvn >/dev/null && mvn -v | head -1 || echo 'check install')"
  echo "  Node:    $(command -v node >/dev/null && node -v || echo 'check install')"
fi
echo
echo "Next steps:"
echo "  1. Edit .env with your local Postgres credentials"
echo "  2. If you were just added to the docker group, log out/in (or: newgrp docker)"
echo "  3. make up"
