#!/usr/bin/env bash
# build-runtime.sh — builds the jlink runtime image jpackage will bundle for both the CLI
# and the server. Used by release-cli.yml on ubuntu-latest and macos-* runners; see
# build-runtime.ps1 for the Windows equivalent.
#
# Usage: build-runtime.sh <server-quarkus-app-dir> <cli-jar> <runtime-output-dir>
#
# Module set is a union of two different techniques, not one:
#
#  - CLI: `jdeps --print-module-deps` against resolvr-cli.jar. This works cleanly and
#    precisely (verified locally: reports exactly java.base,java.net.http) because the CLI
#    is a plain, single classpath-loaded jar with no dynamic classloading.
#
#  - Server: NOT computed via jdeps against quarkus-run.jar. Verified locally that this
#    doesn't work reliably — quarkus-run.jar's manifest Class-Path only references
#    Quarkus's small `lib/boot/*.jar` bootstrap layer; the real application/library jars
#    (lib/main, app/, quarkus/) are loaded at runtime by Quarkus's own custom
#    classloader (io.quarkus.bootstrap.runner.QuarkusEntryPoint), which is invisible to
#    jdeps' static bytecode analysis starting from that jar. This is a known characteristic
#    of Quarkus's fast-jar layout, not specific to this project. Instead, the server's
#    module contribution is a fixed, documented baseline covering what this project's
#    actual Quarkus extensions need (rest, rest-jackson, config-yaml, scheduler,
#    smallrye-health, smallrye-openapi, mcp-server-sse) plus the modules Quarkus
#    applications commonly touch. Broader than a true minimal set would be, but correct —
#    and still far smaller than bundling a full JDK.

set -euo pipefail

SERVER_DIR="$1"   # .../target/quarkus-app  (unused directly today, kept for signature
                   # symmetry with build-runtime.ps1 and in case a future Quarkus release
                   # makes static analysis of it reliable)
CLI_JAR="$2"      # .../resolvr-cli.jar
OUT_DIR="$3"      # runtime image output directory

if [ -z "${SERVER_DIR:-}" ] || [ -z "${CLI_JAR:-}" ] || [ -z "${OUT_DIR:-}" ]; then
  echo "Usage: $0 <server-quarkus-app-dir> <cli-jar> <runtime-output-dir>" >&2
  exit 1
fi

CLI_MODULES="$(jdeps --print-module-deps --ignore-missing-deps "$CLI_JAR")"
echo "CLI modules (jdeps-computed): $CLI_MODULES"

SERVER_MODULES="java.base,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,java.xml,java.logging,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.security.auth,jdk.httpserver,jdk.management,jdk.jfr,jdk.charsets,jdk.zipfs"
# jdk.charsets and jdk.zipfs are NOT optional, despite jlink/jpackage not erroring without
# them: confirmed locally that omitting jdk.charsets produces a runtime image where the
# packaged server exits immediately (code 1, zero stdout/stderr, no exception printed
# anywhere) on Windows — JEP 400's stdout/stderr encoding falls back to the native console
# codepage there, which needs classes this module provides; without it the JVM appears to
# fail before the application (or even a logger) is up enough to report why. jdk.zipfs is
# added defensively alongside it for the same class of "silent until you hit it" failure —
# NIO ZipFileSystem support some dependency may reach for at runtime.
echo "Server modules (fixed baseline — see script header for why): $SERVER_MODULES"

ALL_MODULES="$(printf '%s,%s\n' "$CLI_MODULES" "$SERVER_MODULES" | tr ',' '\n' | sort -u | paste -sd, -)"
echo "Combined module set: $ALL_MODULES"

rm -rf "$OUT_DIR"
jlink --add-modules "$ALL_MODULES" \
  --output "$OUT_DIR" \
  --strip-debug --no-header-files --no-man-pages

echo "Runtime image built at $OUT_DIR"
"$OUT_DIR/bin/java" -version
