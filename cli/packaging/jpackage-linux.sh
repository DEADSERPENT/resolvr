#!/usr/bin/env bash
# jpackage-linux.sh — builds Linux artifacts from a staged jpackage input directory (see
# assemble-input.sh) and a jlink runtime image (see build-runtime.sh). Like the Windows/macOS
# scripts, the launcher runs the CLI (com.resolvr.cli.Main), never the server jar directly —
# the server is only ever started by the CLI's own `start` command (InstalledJarLaunchSpec).
#
# Builds whichever of app-image/deb/rpm are listed in TYPES (comma-separated). app-image is
# always tar'd into resolvr-cli-<version>-linux-<arch>.tar.gz; deb/rpm are jpackage's native
# output, renamed to the required filenames.
#
#   x64 target:   app-image,deb,rpm   -> .tar.gz + .deb + .rpm
#   arm64 target: app-image           -> .tar.gz only (see release-cli.yml for why arm64 is
#                                         tar.gz-only: this repo's release pipeline builds it
#                                         on a real GitHub-hosted arm64 Linux runner — no
#                                         cross-compilation — and .deb/.rpm weren't requested
#                                         for that target in the distribution plan)
#
# deb requires dpkg-deb + fakeroot on PATH; rpm requires rpmbuild. Neither is installed by
# default on ubuntu-latest — release-cli.yml installs them before calling this script.
#
# Usage: jpackage-linux.sh <stage-dir> <runtime-dir> <version> <arch: x64|arm64> <dest-dir> <types-csv>

set -euo pipefail

STAGE_DIR="$1"
RUNTIME_DIR="$2"
VERSION="$3"
ARCH="$4"
DEST_DIR="$5"
TYPES_CSV="$6"

if [ -z "${TYPES_CSV:-}" ]; then
  echo "Usage: $0 <stage-dir> <runtime-dir> <version> <arch: x64|arm64> <dest-dir> <types-csv>" >&2
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
    if [ "$HOST_ARCH" != "aarch64" ] && [ "$HOST_ARCH" != "arm64" ]; then
      echo "ERROR: requested arch arm64 but this runner is $HOST_ARCH — refusing to mislabel a cross-arch build." >&2
      exit 1
    fi
    ;;
  *)
    echo "ERROR: unknown arch '$ARCH' (expected x64 or arm64)" >&2
    exit 1
    ;;
esac

mkdir -p "$DEST_DIR"
IFS=',' read -ra TYPES <<< "$TYPES_CSV"

for TYPE in "${TYPES[@]}"; do
  TEMP_DEST="$(mktemp -d)"

  case "$TYPE" in
    app-image)
      jpackage \
        --type app-image \
        --input "$STAGE_DIR" \
        --runtime-image "$RUNTIME_DIR" \
        --dest "$TEMP_DEST" \
        --name resolvr \
        --app-version "$VERSION" \
        --vendor "Resolvr" \
        --description "Resolvr - local-first AI code-review resolution CLI" \
        --main-jar cli/resolvr-cli.jar \
        --main-class com.resolvr.cli.Main

      APP_DIR="$TEMP_DEST/resolvr"
      if [ ! -d "$APP_DIR" ]; then
        echo "ERROR: jpackage did not produce an app-image at $APP_DIR" >&2
        exit 1
      fi
      TARBALL="$DEST_DIR/resolvr-cli-$VERSION-linux-$ARCH.tar.gz"
      tar -C "$TEMP_DEST" -czf "$TARBALL" resolvr
      echo "Built $TARBALL"
      ;;

    deb)
      jpackage \
        --type deb \
        --input "$STAGE_DIR" \
        --runtime-image "$RUNTIME_DIR" \
        --dest "$TEMP_DEST" \
        --name resolvr \
        --app-version "$VERSION" \
        --vendor "Resolvr" \
        --description "Resolvr - local-first AI code-review resolution CLI" \
        --main-jar cli/resolvr-cli.jar \
        --main-class com.resolvr.cli.Main \
        --linux-package-name resolvr \
        --linux-deb-maintainer "resolvr@users.noreply.github.com" \
        --linux-menu-group "Development" \
        --linux-shortcut

      BUILT="$(find "$TEMP_DEST" -maxdepth 1 -name '*.deb' | head -n1)"
      if [ -z "$BUILT" ]; then
        echo "ERROR: jpackage did not produce a .deb in $TEMP_DEST" >&2
        exit 1
      fi
      FINAL="$DEST_DIR/resolvr-cli-$VERSION-linux-$ARCH.deb"
      mv "$BUILT" "$FINAL"
      echo "Built $FINAL"
      ;;

    rpm)
      jpackage \
        --type rpm \
        --input "$STAGE_DIR" \
        --runtime-image "$RUNTIME_DIR" \
        --dest "$TEMP_DEST" \
        --name resolvr \
        --app-version "$VERSION" \
        --vendor "Resolvr" \
        --description "Resolvr - local-first AI code-review resolution CLI" \
        --main-jar cli/resolvr-cli.jar \
        --main-class com.resolvr.cli.Main \
        --linux-package-name resolvr \
        --linux-menu-group "Development" \
        --linux-shortcut

      BUILT="$(find "$TEMP_DEST" -maxdepth 1 -name '*.rpm' | head -n1)"
      if [ -z "$BUILT" ]; then
        echo "ERROR: jpackage did not produce an .rpm in $TEMP_DEST" >&2
        exit 1
      fi
      FINAL="$DEST_DIR/resolvr-cli-$VERSION-linux-$ARCH.rpm"
      mv "$BUILT" "$FINAL"
      echo "Built $FINAL"
      ;;

    *)
      echo "ERROR: unknown type '$TYPE' (expected app-image, deb, or rpm)" >&2
      exit 1
      ;;
  esac

  rm -rf "$TEMP_DEST"
done
