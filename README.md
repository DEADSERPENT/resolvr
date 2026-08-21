# Resolvr

[![CI](https://github.com/DEADSERPENT/pr-copilot-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/DEADSERPENT/pr-copilot-bridge/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![Quarkus](https://img.shields.io/badge/Quarkus-3.15-blue)
![MCP](https://img.shields.io/badge/protocol-MCP-6c5ce7)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

GitHub Copilot can review a PR and leave comments — but it can't fix anything. Resolvr bridges that gap: it watches for Copilot's review comments and hands them to your IDE coding agent (VS Code / JetBrains, Agent Mode), which reads the file, writes the fix, commits it, and resolves the thread — automatically.

## How it works

```
Copilot review comment → webhook (HMAC-verified) → Resolvr queues it
                                                          │
IDE agent  ──MCP/SSE──▶  poll → fetch thread → read file → write fix
                                                          │
                          Resolvr  ──REST + GraphQL──▶  commit + resolve on GitHub
```

Resolvr never calls an LLM itself — it's the plumbing between "Copilot left feedback on GitHub" and "an agent with edit access is sitting in your editor," not another agent.

## Engineering highlights

- MCP tool server (Quarkus/SSE) exposing 11 tools, including a full stage/confirm/discard workflow for human-in-the-loop review
- HMAC-SHA256 webhook verification + optimistic concurrency (sha-checked commits, conflicts surfaced as actionable errors)
- Retry/backoff shared across both GitHub clients — honors rate-limit headers, cursor-paginates GraphQL past the 100-node limit
- 53 tests, ~71% coverage, verified live end-to-end against a real GitHub repo (real webhook, real commit, real thread resolution)
- CI/CD: every push to `main` tests, then builds and publishes a Docker image to GHCR — deploying needs no Java toolchain

Full breakdown: [Architecture](docs/ARCHITECTURE.md) · [Testing](docs/TESTING.md) · [Security](docs/SECURITY.md) · [Limitations](docs/LIMITATIONS.md)

## Quick start

1. **Deploy** — [![Deploy to Koyeb](https://www.koyeb.com/static/images/deploy/button.svg)](https://app.koyeb.com/deploy?type=docker&image=ghcr.io/deadserpent/resolvr%3Alatest&name=resolvr&ports=8080%3Bhttp%3B/&env%5BGITHUB_TOKEN%5D=&env%5BGITHUB_WEBHOOK_SECRET%5D=&env%5BRESOLVR_API_KEY%5D=&env%5BRESOLVR_REQUIRE_CONFIRMATION%5D=false) or `docker pull ghcr.io/deadserpent/resolvr:latest` on any Docker host, port `8080`.
2. **Webhook** — on the repo you want reviewed: *Settings → Webhooks*, URL `https://<host>/webhook/github`, secret matching `GITHUB_WEBHOOK_SECRET`, events "Pull request reviews" + "review comments".
3. **Connect your IDE** — point it at `https://<host>/mcp/sse`. Ready-to-use configs are checked into this repo at `.vscode/mcp.json` and `.idea/mcp.xml`.

Then just ask your agent: *"Use Resolvr to fix PR #42's review comments."*

## Configuration

| Variable | What it does | Required |
|---|---|---|
| `GITHUB_TOKEN` | Reads files, commits fixes. `repo`, `read:discussion`, `write:discussion` scopes. Falls back to `gh auth token` locally. | Yes in production |
| `GITHUB_WEBHOOK_SECRET` | Verifies incoming webhooks. | Strongly recommended |
| `RESOLVR_API_KEY` | Required `Authorization: Bearer` for every route except the webhook and health check. | Strongly recommended |
| `RESOLVR_REQUIRE_CONFIRMATION` | Stage fixes instead of auto-committing; `confirm_fix` before anything reaches GitHub. | Optional, default `false` |

Building from source instead? See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Full tool list: [docs/TOOLS.md](docs/TOOLS.md).

MIT licensed — see [LICENSE](LICENSE).
