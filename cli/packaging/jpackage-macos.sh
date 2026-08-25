#!/usr/bin/env bash
# jpackage-macos.sh — builds resolvr-cli-<version>-macos-<arch>.pkg from a staged jpackage
# input directory (see assemble-input.sh) and a jlink runtime image (see build-runtime.sh).
# jlink/jpackage always build for the host's own architecture — there is no cross-arch
# jpackage build here; release-cli.yml runs this once on a real x64 runner (macos-13) and
# once on a real arm64 runner (macos-14/macos-latest) to get both .pkg artifacts, matching
# "do not fake or cross-compile" for the arm64 target.
#
# Like jpackage-windows.ps1's MSI, the launcher runs the CLI (com.resolvr.cli.Main), never
# the server jar directly — the server is only ever started by the CLI's own `start` command
# (InstalledJarLaunchSpec), same fail-closed path as a developer checkout.
#
# Unsigned: no Apple Developer ID is available in this environment (none was invented — see
# docs/INSTALLATION.md's Signing section). --mac-sign is intentionally omitted. The resulting
# .pkg will trigger Gatekeeper's "unidentified developer" prompt on first run; documented for
# users, and the workflow is structured so a signing/notarization step can be inserted later
# once a Developer ID + notarization credentials exist (see the commented block in
# release-cli.yml).
#
# Usage: jpackage-macos.sh <stage-dir> <runtime-dir> <version> <arch: x64|arm64> <dest-dir>

set -euo pipefail

STAGE_DIR="$1"
RUNTIME_DIR="$2"
VERSION="$3"
ARCH="$4"
DEST_DIR="$5"

if [ -z "${STAGE_DIR:-}" ] || [ -z "${RUNTIME_DIR:-}" ] || [ -z "${VERSION:-}" ] || [ -z "${ARCH:-}" ] || [ -z "${DEST_DIR:-}" ]; then
  echo "Usage: $0 <stage-dir> <runtime-dir> <version> <arch: x64|arm64> <dest-dir>" >&2
  exit 1
fi
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ERROR: version must be X.Y.Z, got '$VERSION'" >&2
  exit 1
fi

HOST_ARCH="$(uname -m)"
case "$ARCH" in
  x64)
    if [ "$HOST_ARCH" != "x86_64" ]; then
      echo "ERROR: requested arch x64 but this runner is $HOST_ARCH — refusing to mislabel a cross-arch build." >&2
      exit 1
    fi
    ;;
  arm64)
    if [ "$HOST_ARCH" != "arm64" ]; then
      echo "ERROR: requested arch arm64 but this runner is $HOST_ARCH — refusing to mislabel a cross-arch build." >&2
      exit 1
    fi
    ;;
  *)
    echo "ERROR: unknown arch '$ARCH' (expected x64 or arm64)" >&2
    exit 1
    ;;
esac

TEMP_DEST="$(mktemp -d)"
trap 'rm -rf "$TEMP_DEST"' EXIT

jpackage \
  --type pkg \
  --input "$STAGE_DIR" \
  --runtime-image "$RUNTIME_DIR" \
  --dest "$TEMP_DEST" \
  --name resolvr \
  --app-version "$VERSION" \
  --vendor "Resolvr" \
  --description "Resolvr - local-first AI code-review resolution CLI" \
  --main-jar cli/resolvr-cli.jar \
  --main-class com.resolvr.cli.Main \
  --mac-package-identifier com.resolvr.cli

BUILT="$(find "$TEMP_DEST" -maxdepth 1 -name '*.pkg' | head -n1)"
if [ -z "$BUILT" ]; then
  echo "ERROR: jpackage did not produce a .pkg in $TEMP_DEST" >&2
  exit 1
fi

mkdir -p "$DEST_DIR"
FINAL="$DEST_DIR/resolvr-cli-$VERSION-macos-$ARCH.pkg"
mv "$BUILT" "$FINAL"

echo "Built $FINAL"
