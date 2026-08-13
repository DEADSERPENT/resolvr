#!/usr/bin/env bash
# run.sh — build and run the Resolvr MCP server

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# ── Validate required env vars ────────────────────────────────────────────────
if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  echo "ERROR: GITHUB_TOKEN environment variable is not set."
  echo "  export GITHUB_TOKEN=ghp_your_token_here"
  exit 1
fi

# ── Build ─────────────────────────────────────────────────────────────────────
echo ">>> Building Resolvr..."
cd "$PROJECT_DIR"
./mvnw package -DskipTests -q

# ── Run ───────────────────────────────────────────────────────────────────────
echo ">>> Starting MCP bridge on http://localhost:8080"
echo "    IDE MCP endpoint : http://localhost:8080/mcp/sse"
echo "    GitHub webhook   : http://localhost:8080/webhook/github"
echo "    Manual trigger   : http://localhost:8080/webhook/trigger?owner=ORG&repo=REPO&pr=42"
echo "    Swagger UI       : http://localhost:8080/swagger-ui"
echo ""

java \
  -Xms64m -Xmx256m \
  -XX:+UseG1GC \
  -Dquarkus.http.port="${PORT:-8080}" \
  -jar target/quarkus-app/quarkus-run.jar
