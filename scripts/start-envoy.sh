#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_PATH="$ROOT_DIR/configs/external-services/envoy.yaml"

exec envoy -c "$CONFIG_PATH" "$@"
