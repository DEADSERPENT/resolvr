# Resolvr

[![CI](https://github.com/DEADSERPENT/resolvr/actions/workflows/ci.yml/badge.svg)](https://github.com/DEADSERPENT/resolvr/actions/workflows/ci.yml) [![Latest Release](https://img.shields.io/github/v/release/DEADSERPENT/resolvr)](https://github.com/DEADSERPENT/resolvr/releases/latest) ![MCP](https://img.shields.io/badge/protocol-MCP-6c5ce7) ![License](https://img.shields.io/badge/license-MIT-lightgrey)

**The bridge between a GitHub PR review and the coding agent that fixes it.**

GitHub Copilot can review your pull request and leave comments — but turning those comments into verified code changes, tests, and resolved review threads still requires a workflow. Someone still has to open the editor, read every thread, fix the code, and resolve each conversation by hand. Resolvr closes that gap: it connects your coding agent to the PR for your current branch, hands it the review threads and CI status, and — once you explicitly approve the fix — commits, pushes, and resolves the addressed threads for you.

Resolvr is not a coding agent, and it doesn't replace GitHub Copilot or Claude Code. It runs no LLM of its own — it's the trusted plumbing that lets an agent you already trust safely turn PR feedback into a pushed commit.

## Built for GitHub Copilot

Resolvr's primary use case is resolving **GitHub Copilot** PR reviews. It works with Copilot **Agent Mode** in **VS Code** and **JetBrains** IDEs, and with **Claude Code** — any of these connect to Resolvr the same way, over the Model Context Protocol (MCP).

## How it works

```
GitHub PR Review → Coding Agent → Resolvr → Local Fix → Tests
                                                              │
                                                       Human Approval
                                                              │
                                                    Commit & Push → Review Resolved
```

Your agent asks Resolvr what PR the current workspace belongs to, reads the unresolved threads and CI status, and fixes the code locally using its own editing tools and test runner. Nothing is written back to GitHub until you say so — Resolvr then commits exactly that change, pushes it, and resolves the threads it addressed.

## Why Resolvr

- **One approval-gated write path** — a single tool ever commits or pushes, and it never runs without your explicit sign-off in between
- **Trust, but verify** — before writing anything, Resolvr independently re-checks branch, HEAD, and PR state against Git and GitHub, rather than trusting what the agent claims it changed
- **Fail-closed by default** — a production instance refuses to start without an API key configured; there's no accidental "open" mode
- **Built-in CI feedback loop** — poll-friendly check status and failure-log excerpts, so your agent can diagnose and re-fix without leaving the editor
- **No webhooks, no queues, no background polling** — your agent drives every interaction; Resolvr only acts when asked
- **Runs anywhere** — self-hosted via Docker, or installed as a native CLI on Windows, macOS, or Linux with no Java or Maven required

## Quick installation

Grab the latest release for your platform from the [**GitHub Releases page**](https://github.com/DEADSERPENT/resolvr/releases/latest).

| Platform | Package |
|---|---|
| Windows | `resolvr-cli-<version>-win-x64.msi` |
| macOS | `resolvr-cli-<version>-macos-x64.pkg` (Intel) / `-arm64.pkg` (Apple Silicon) |
| Linux | `.deb` / `.rpm`, or a portable `.tar.gz` (no root required) |

Each installer bundles its own JVM — no separate Java install needed. Once installed:

```sh
resolvr doctor   # sanity-check your environment
resolvr start    # start the server
```

Full platform-by-platform steps, PATH setup, and uninstall instructions: [**docs/INSTALLATION.md**](docs/INSTALLATION.md).

## Connect your agent

Resolvr exposes an MCP endpoint at `http://<host>:8080/mcp/sse`, authenticated with `Authorization: Bearer <RESOLVR_API_KEY>`.

- **VS Code / JetBrains (Copilot Agent Mode)** — point your IDE's MCP client at that endpoint. Ready-to-use configs are checked into this repo at `.vscode/mcp.json` and `.idea/mcp.xml`.
- **Claude Code** — add Resolvr as an MCP server pointing at the same endpoint.

Then just ask your agent: *"Use Resolvr to fix this PR's review comments. Don't push until I approve."*

## Security & human approval

Resolvr is designed so a coding agent can never push to GitHub on its own:

- `commit_and_push_resolution` is the **only** tool that ever commits or pushes, and it only runs after you've explicitly approved a staged resolution
- Every write is re-verified against live Git and GitHub state first — stale or unexpected changes are refused rather than pushed
- A production instance **will not start** without `RESOLVR_API_KEY` configured
- Secrets (`GITHUB_TOKEN`, `RESOLVR_API_KEY`) are never echoed back in a tool response, log, or commit message

Full details: [**docs/SECURITY.md**](docs/SECURITY.md).

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — how the pieces fit together
- [Installation](docs/INSTALLATION.md) — platform installers, PATH, uninstall/upgrade
- [Development](docs/DEVELOPMENT.md) — building and running from source
- [Security](docs/SECURITY.md) — the approval boundary, fail-closed startup, token permissions
- [MCP tools reference](docs/TOOLS.md) — every tool an agent can call
- [Limitations](docs/LIMITATIONS.md) — what this doesn't do yet
- [Latest release](https://github.com/DEADSERPENT/resolvr/releases/latest)

MIT licensed — see [LICENSE](LICENSE).
