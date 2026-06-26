#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mkdir -p "$ROOT_DIR/logs"
cd "$ROOT_DIR"

export AUTH_LOGGING_FILE_NAME="$ROOT_DIR/logs/clippy-auth-server.log"

exec mvn -q -pl auth/server spring-boot:run "$@"
