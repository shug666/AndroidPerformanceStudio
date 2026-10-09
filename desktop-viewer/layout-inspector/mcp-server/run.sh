#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_VIEWER="$(cd "$DIR/../.." && pwd)"
JAR_DIR="$DIR/build/libs"

JAR="$(ls "$JAR_DIR"/layout-inspector-mcp-*.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ]; then
  echo "[run-mcp] fat jar not found, building via shadowJar..."
  "$DESKTOP_VIEWER/gradlew" -p "$DESKTOP_VIEWER/layout-inspector" :mcp-server:shadowJar
  JAR="$(ls "$JAR_DIR"/layout-inspector-mcp-*.jar | head -1)"
fi

echo "[run-mcp] starting: $JAR"
echo "[run-mcp] bridge port: ${APS_BRIDGE_PORT:-7821} (override with APS_BRIDGE_PORT)"
exec java -jar "$JAR" "$@"