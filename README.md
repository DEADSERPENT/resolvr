# Resolvr

[![CI](https://github.com/DEADSERPENT/pr-copilot-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/DEADSERPENT/pr-copilot-bridge/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![Quarkus](https://img.shields.io/badge/Quarkus-3.15-blue)
![MCP](https://img.shields.io/badge/protocol-MCP-6c5ce7)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

GitHub Copilot can review your pull requests and leave comments — but it can't fix anything it finds. Resolvr closes that gap: it watches for Copilot's review comments and hands them to your coding agent (VS Code or JetBrains, in Agent Mode) so it can read the file, write the fix, commit it, and mark the comment resolved — without you copy-pasting review feedback into your editor by hand.

## How it works

```
GitHub Copilot review comment
        │  webhook (HMAC-SHA256 verified)
        ▼
Resolvr  ──enqueue──▶  in-memory event queue
        │
        │  MCP over SSE (/mcp/sse)
        ▼
Your IDE agent (VS Code / JetBrains Copilot Agent Mode)
        │  poll → fetch unresolved threads → read file → write fix
        ▼
Resolvr  ──REST + GraphQL──▶  commits the fix, resolves the thread on GitHub
```

Resolvr itself never calls an LLM — it's a bridge, not another agent. GitHub's hosted Copilot reviewer can leave comments but can't act on them; only an IDE-side agent can hold an MCP connection and actually edit/commit code. Resolvr is the piece that sits between "Copilot left feedback on GitHub" and "an agent with edit access is sitting in your editor," forwarding one to the other and doing the GitHub plumbing (webhooks, REST, GraphQL, retries, auth) so the agent doesn't have to.

## Engineering highlights

- **MCP tool server** (Quarkus SSE transport) exposing 11 tools an IDE agent drives autonomously — poll, fetch, read, fix, resolve, plus a full stage/confirm/discard workflow for human-in-the-loop review.
- **HMAC-SHA256 webhook verification**, constant-time comparison, skipped only in dev mode.
- **Optimistic concurrency on commits** — every fix is written against the file's current `sha`; a conflicting concurrent commit surfaces as an actionable "file changed since last read, re-fetch it" instead of a raw 409.
- **Retry/backoff layer** shared by both GitHub clients — exponential backoff on 5xx and transient I/O errors, honors `Retry-After`/`X-RateLimit-Reset` on 429/403, aborts fast rather than blocking on a long rate-limit window.
- **Cursor-paginated GraphQL** for PRs with more than 100 review threads (past GitHub's single-page node limit).
- **Confirmation-mode staging** — an optional checkpoint (`RESOLVR_REQUIRE_CONFIRMATION`) where fixes are held until an explicit `confirm_fix` call, so nothing reaches a real repo unreviewed.
- **53 tests, ~74% coverage** (JUnit 5 against real local HTTP servers, not mocks) covering the happy path, self-healing failure modes (retries, pagination, partial-batch failures), and hard failure modes (stale-sha conflicts, oversized files, malformed webhooks, auth rejection) — see [docs/TESTING.md](docs/TESTING.md) for the full breakdown, including a live run against a real GitHub repo (real webhook delivery, real commit, real thread resolution, independently verified via the GitHub API).
- **Zero-config local dev** — falls back to `gh auth token` when no PAT is set, so `./mvnw quarkus:dev` needs nothing beyond `gh auth login`.
- **CI/CD** — every push to `main` runs the full test suite, then builds and publishes a multi-stage Docker image to GHCR, so deploying needs no Java toolchain at all.

## What you get

- Copilot leaves a review comment → Resolvr notices and queues it
- Your IDE agent fetches the comment, reads the file it's about, and proposes a fix
- The fix gets committed and the comment marked resolved — automatically
- Optional approval step: turn on confirmation mode and nothing gets committed until you say so

## Set it up

### 1. Deploy Resolvr

Click through — no code, no build step:

[![Deploy to Koyeb](https://www.koyeb.com/static/images/deploy/button.svg)](https://app.koyeb.com/deploy?type=docker&image=ghcr.io/deadserpent/resolvr%3Alatest&name=resolvr&ports=8080%3Bhttp%3B/&env%5BGITHUB_TOKEN%5D=&env%5BGITHUB_WEBHOOK_SECRET%5D=&env%5BRESOLVR_API_KEY%5D=&env%5BRESOLVR_REQUIRE_CONFIRMATION%5D=false)

On the deploy screen, fill in three values (see [Configuration](#configuration) below) — Koyeb's free tier stays running, which matters here since Resolvr needs to be listening whenever GitHub sends it something. Prefer a different host? Any platform that runs a Docker image works: `docker pull ghcr.io/deadserpent/resolvr:latest`, expose port `8080`, set the same three environment variables.

Once it's deployed you'll have a public URL, e.g. `https://resolvr-yourname.koyeb.app`.

### 2. Tell GitHub about it

On the repo (or organization, to cover every repo at once) you want reviewed:

**Settings → Webhooks → Add webhook**
- Payload URL: `https://<your-resolvr-host>/webhook/github`
- Content type: `application/json`
- Secret: the same value you set as `GITHUB_WEBHOOK_SECRET`
- Events: "Pull request reviews" and "Pull request review comments"

### 3. Connect your IDE

In the repo you want Copilot's fixes applied to (not this one), add an MCP config:

**VS Code** — `.vscode/mcp.json`:
```json
{
  "inputs": [
    {
      "type": "promptString",
      "id": "resolvr-api-key",
      "description": "Resolvr API key",
      "password": true
    }
  ],
  "servers": {
    "resolvr": {
      "type": "sse",
      "url": "https://<your-resolvr-host>/mcp/sse",
      "label": "Resolvr",
      "headers": {
        "Authorization": "Bearer ${input:resolvr-api-key}"
      }
    }
  }
}
```

**JetBrains** — `.idea/mcp.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="McpServersConfiguration">
    <mcpServers>
      <server name="resolvr" type="sse" url="https://<your-resolvr-host>/mcp/sse"/>
    </mcpServers>
  </component>
</project>
```
JetBrains' MCP client varies by version in how it sends custom headers — see [Security](docs/SECURITY.md) if `RESOLVR_API_KEY` requests are getting rejected.

Reload your IDE window and Copilot Agent Mode will see Resolvr as an available tool.

## Configuration

Set these when you deploy — on Koyeb's deploy screen, or as environment variables on whatever host you use:

| Variable | What it does | Required |
|---|---|---|
| `GITHUB_TOKEN` | A GitHub token Resolvr uses to read files and commit fixes. Needs `repo`, `read:discussion`, `write:discussion` scopes. Leave unset locally to fall back to `gh auth token`. | Yes (in production) |
| `GITHUB_WEBHOOK_SECRET` | Must match the secret you set on the GitHub webhook — Resolvr rejects anything that doesn't verify against it. | Strongly recommended |
| `RESOLVR_API_KEY` | The key your IDE sends as `Authorization: Bearer <key>`. Without it, anyone who can reach your deployed URL can drive your GitHub token. | Strongly recommended |
| `RESOLVR_REQUIRE_CONFIRMATION` | `true` stages every fix instead of committing it immediately — nothing reaches GitHub until you explicitly confirm it in your IDE. Defaults to `false`. | Optional |

## Using it

Once connected, just ask your agent to use it — e.g. *"Check Resolvr for pending reviews on this PR"* or *"Use Resolvr to fix PR #42's review comments."* The agent drives the rest itself: it finds the unresolved comments, reads the relevant files, applies fixes, and resolves each thread. Full tool list in [docs/TOOLS.md](docs/TOOLS.md).

## Good to know

- **One GitHub token, shared.** Resolvr isn't multi-tenant — whatever `GITHUB_TOKEN` you set can reach exactly the repos that token can reach. Fine for a personal setup or a small team sharing one deployment; not built for hosting strangers' repos.
- **Your infrastructure, your data.** Resolvr runs entirely on whatever host you deploy it to — nothing is sent to a third-party service beyond GitHub's own API.
- **Building from source or contributing instead?** See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Learn more

- [Architecture](docs/ARCHITECTURE.md) — how the pieces fit together
- [MCP tools reference](docs/TOOLS.md) — the full tool list your IDE agent can call
- [Testing](docs/TESTING.md) — test suite, coverage, and how failure scenarios (including a live GitHub run) are handled
- [Security](docs/SECURITY.md) — API key auth, confirmation mode, token permission gotchas
- [Known limitations](docs/LIMITATIONS.md) — what this doesn't do (yet)

MIT licensed — see [LICENSE](LICENSE).
