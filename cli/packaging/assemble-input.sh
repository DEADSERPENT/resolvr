#!/usr/bin/env bash
# assemble-input.sh — stages a jpackage --input directory in the exact shape
# InstallationLocator expects to find at runtime:
#
#   <stage>/cli/resolvr-cli.jar
#   <stage>/server/quarkus-app/...        (the server's full Quarkus fast-jar tree)
#
# jpackage copies --input verbatim into the app image (app/ on Windows, lib/app/ on
# Linux, Contents/app/ on macOS), so this stage directory's shape IS the installed
# layout's app/ directory, unchanged. See InstallationLocator's class javadoc for the
# full per-OS mapping this depends on.
#
# Usage: assemble-input.sh <cli-jar> <server-quarkus-app-dir> <stage-dir>

set -euo pipefail

CLI_JAR="$1"          # .../cli/target/resolvr-cli.jar
SERVER_QUARKUS_APP="$2"  # .../target/quarkus-app
STAGE_DIR="$3"

if [ -z "${CLI_JAR:-}" ] || [ -z "${SERVER_QUARKUS_APP:-}" ] || [ -z "${STAGE_DIR:-}" ]; then
  echo "Usage: $0 <cli-jar> <server-quarkus-app-dir> <stage-dir>" >&2
  exit 1
fi
if [ ! -f "$CLI_JAR" ]; then
  echo "ERROR: CLI jar not found: $CLI_JAR" >&2
  exit 1
fi
if [ ! -d "$SERVER_QUARKUS_APP" ]; then
  echo "ERROR: server quarkus-app directory not found: $SERVER_QUARKUS_APP" >&2
  exit 1
fi

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/cli" "$STAGE_DIR/server"

cp "$CLI_JAR" "$STAGE_DIR/cli/resolvr-cli.jar"
cp -R "$SERVER_QUARKUS_APP" "$STAGE_DIR/server/quarkus-app"

echo "Staged jpackage input at $STAGE_DIR:"
find "$STAGE_DIR" -maxdepth 3
