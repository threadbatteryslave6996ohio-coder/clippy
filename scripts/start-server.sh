#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-/tmp/clippy-m2}"

mkdir -p "$ROOT_DIR/logs"
mkdir -p "$MAVEN_REPO_LOCAL"
cd "$ROOT_DIR"

MAVEN_OPTS="${MAVEN_OPTS:-}" mvn -q -Dmaven.repo.local="$MAVEN_REPO_LOCAL" -pl server -am -Dmaven.test.skip=true package "$@"
exec java -jar server/target/clippy-server-0.1.0-SNAPSHOT.jar
