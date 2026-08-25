# Resolvr

[![CI](https://github.com/DEADSERPENT/pr-copilot-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/DEADSERPENT/pr-copilot-bridge/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![Quarkus](https://img.shields.io/badge/Quarkus-3.15-blue)
![MCP](https://img.shields.io/badge/protocol-MCP-6c5ce7)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

GitHub Copilot can review a PR and leave comments — but it can't fix anything. Resolvr bridges that gap: your IDE coding agent (VS Code / JetBrains, Agent Mode; Claude Code) discovers the PR for your current branch, reads the review threads and CI status, fixes the code locally with its own tools, and — only after you explicitly approve — Resolvr commits and pushes exactly that change and resolves the addressed threads.

## How it works

```
IDE agent ──MCP/SSE──▶ get_workspace_pr_context → get_pr_context
                              │ (threads, CI status, diff — all read-only)
                        agent fixes files + runs tests locally
                              │
                        prepare_resolution_summary (stages an approval package)
                              │
                    developer explicitly approves
                              │
                        commit_and_push_resolution ──REST──▶ GitHub (commit + push)
                              │
                        resolve_addressed_threads  ──GraphQL──▶ GitHub (resolve threads)
```

This local-first flow is Resolvr's only mode — there is exactly one tool that ever commits or
pushes, and it never runs without an explicit approval in between. There is no webhook, no
server-side queue, and no background polling: the coding agent is the orchestration layer, and
Resolvr is the trusted MCP execution, verification, and safety layer underneath it.

Resolvr never calls an LLM itself — it's the plumbing between GitHub and an agent with edit access
sitting in your editor, not another agent.

## Engineering highlights

- MCP tool server (Quarkus/SSE) with a single approval-gated write path — no tool commits or pushes without an explicit developer approval in between
- Every write re-verifies branch/HEAD/PR state against Git and GitHub independently before committing, rather than trusting what the agent claims it changed
- Fail-closed startup: a packaged (non-dev) instance refuses to boot without an API key configured
- Retry/backoff shared across both GitHub clients — honors rate-limit headers, cursor-paginates GraphQL past the 100-node limit
- CI feedback loop: poll-friendly check status + truncated failure-log excerpts, so an agent can diagnose and re-fix without leaving the editor
- CI/CD: every push to `main` tests, then builds and publishes a Docker image to GHCR — deploying needs no Java toolchain

Full breakdown: [Architecture](docs/ARCHITECTURE.md) · [Testing](docs/TESTING.md) · [Security](docs/SECURITY.md) · [Limitations](docs/LIMITATIONS.md)

## Quick start

1. **Deploy** — [![Deploy to Koyeb](https://www.koyeb.com/static/images/deploy/button.svg)](https://app.koyeb.com/deploy?type=docker&image=ghcr.io/deadserpent/resolvr%3Alatest&name=resolvr&ports=8080%3Bhttp%3B/&env%5BGITHUB_TOKEN%5D=&env%5BRESOLVR_API_KEY%5D=) or `docker pull ghcr.io/deadserpent/resolvr:latest` on any Docker host, port `8080`. `RESOLVR_API_KEY` is required outside dev/test — the server refuses to start without it.
2. **Connect your IDE** — point it at `https://<host>/mcp/sse` (with `Authorization: Bearer <RESOLVR_API_KEY>`). Ready-to-use configs are checked into this repo at `.vscode/mcp.json` and `.idea/mcp.xml`.

Then just ask your agent: *"Use Resolvr to fix this PR's review comments. Don't push until I approve."*

## Configuration

| Variable | What it does | Required |
|---|---|---|
| `GITHUB_TOKEN` | Reads files, commits fixes. `repo`, `read:discussion`, `write:discussion` scopes. Falls back to `gh auth token` locally. | Yes in production |
| `RESOLVR_API_KEY` | Required `Authorization: Bearer` for every route except the health check. | **Required outside dev/test — startup fails without it** |

Building from source instead? See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Full tool list: [docs/TOOLS.md](docs/TOOLS.md).

MIT licensed — see [LICENSE](LICENSE).
