#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mkdir -p "$ROOT_DIR/logs"
cd "$ROOT_DIR"

if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ROOT_DIR/.env"
  set +a
fi

export AUTH_LOGGING_FILE_NAME="$ROOT_DIR/logs/clippy-auth-server.log"

exec mvn -q -pl auth/server spring-boot:run "$@"
