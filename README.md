# Resolvr

Resolvr closes the loop between GitHub Copilot's PR code review and your IDE coding agent. Copilot can leave review comments on a pull request, but it can't act on them — it has no way to run tools or commit a fix. Resolvr bridges that gap: it watches for new Copilot review comments and hands them to your IDE agent (VS Code, JetBrains, Claude Code) to read, fix, commit, and resolve — automatically.

## Quick start

```bash
export GITHUB_TOKEN=ghp_your_token_here          # repo, read:discussion, write:discussion scopes
export GITHUB_WEBHOOK_SECRET=your_webhook_secret  # HMAC secret for your GitHub webhook
export RESOLVR_API_KEY=$(openssl rand -hex 32)    # required for anything beyond localhost
./scripts/run.sh
```

Point your GitHub repo/org webhook at `http://<your-host>:8080/webhook/github`, then connect your IDE's MCP client to `http://<your-host>:8080/mcp/sse`. VS Code and JetBrains configs are already checked in at `.vscode/mcp.json` and `.idea/mcp.xml`.

Prefer Docker? `docker compose -f docker/docker-compose.yml up --build`.

## What you get

- Copilot leaves a review comment → Resolvr notices and queues it
- Your IDE agent fetches the unresolved thread, reads the file, and proposes a fix
- The fix gets committed and the thread resolved — no manual copy-pasting review comments into your editor
- Turn on `RESOLVR_REQUIRE_CONFIRMATION` if you want a human checkpoint before anything is committed

## Learn more

- [Architecture](docs/ARCHITECTURE.md) — how the pieces fit together
- [MCP tools reference](docs/TOOLS.md) — the full tool list your IDE agent can call
- [Security](docs/SECURITY.md) — API key auth, confirmation mode, token permission gotchas
- [Testing](docs/TESTING.md) — test suite, coverage, and how failure scenarios are handled
- [Known limitations](docs/LIMITATIONS.md) — what this doesn't do (yet)

MIT licensed — see [LICENSE](LICENSE).
