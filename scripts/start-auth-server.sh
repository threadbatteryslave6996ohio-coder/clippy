#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mkdir -p "$ROOT_DIR/logs"
cd "$ROOT_DIR"

exec mvn -q -pl auth/server spring-boot:run "$@"
