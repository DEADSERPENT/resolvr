# Resolvr

A Quarkus MCP server that closes the loop between GitHub Copilot's PR code review and an IDE coding agent, so unresolved review comments get fixed and resolved automatically instead of sitting there.

## Why this exists

GitHub's hosted Copilot code reviewer can read a PR and leave comments on it, but it cannot *act* — it has no way to connect to an MCP server, run tools, or commit a fix. Only IDE-side agents (VS Code / JetBrains Copilot Agent Mode, Claude Code, etc.) can hold an MCP connection and actually do work. That split is the whole reason this project exists: something has to sit between "Copilot left review comments on GitHub" and "an agent with MCP tools and edit/commit access is sitting in your editor," and forward one to the other.

Resolvr is that bridge. It:
1. Listens for GitHub webhook events (`pull_request_review`, `pull_request_review_comment`) and queues them.
2. Exposes an MCP tool surface (SSE transport) that an IDE agent polls to discover pending reviews, fetch unresolved thread context, read files, commit fixes, and resolve threads — all against the real GitHub REST + GraphQL APIs.

## Architecture

```
GitHub  --webhook-->  GitHubWebhookResource  --enqueue-->  PRReviewOrchestrator (in-memory queue)
                                                                     |
IDE Agent (Copilot/Claude)  <--MCP/SSE-->  PRReviewTools  <---------+
                                                 |
                                                 +--> GitHubGraphQLClient  (review threads, resolve)
                                                 +--> GitHubRestClient    (read/commit files)
```

- **`GitHubWebhookResource`** — REST endpoint (`POST /webhook/github`) that verifies the HMAC-SHA256 signature, parses the event, and enqueues it. Also exposes `POST /webhook/trigger` for manual testing without a real webhook, and `GET /webhook/status` for queue depth.
- **`WebhookSignatureVerifier`** — constant-time HMAC-SHA256 verification against `X-Hub-Signature-256`. Skips verification only if no secret is configured (dev mode).
- **`PRReviewOrchestrator`** — bounded in-memory queue (last 500 events) of pending review activity. Drained by the MCP tool layer.
- **`GitHubGraphQLClient`** — fetches unresolved review threads (with cursor pagination past GitHub's 100-node page limit) and resolves threads via `resolveReviewThread`.
- **`GitHubRestClient`** — reads file content and commits fixes via the Contents API, using the current file `sha` for optimistic concurrency.
- **`RetryingHttpSender`** — shared retry/backoff/rate-limit handling for both GitHub clients: exponential backoff on 5xx and transient I/O failures, honors `Retry-After` / `X-RateLimit-Reset` on 429/403, and aborts fast (rather than blocking) if a wait would exceed 30s.
- **`PRReviewTools`** — the MCP tool surface itself (see below).

## MCP tools

| Tool | Purpose |
|---|---|
| `poll_pending_reviews` | Discover PRs with new Copilot review activity |
| `fetch_pr_comments` | Get all unresolved threads for a PR, with prompt-ready context |
| `get_file_content` | Read the current state of a file from the PR branch |
| `apply_fix` | Commit a full-file fix to the PR branch |
| `resolve_thread` | Mark one thread resolved |
| `resolve_all_threads` | Batch-resolve multiple threads |
| `auto_resolve_all` | Commit fixes + resolve threads in one call |

IDE agent instructions for using these tools live in [`.github/copilot-instructions.md`](.github/copilot-instructions.md). VS Code and JetBrains MCP client config are checked in at `.vscode/mcp.json` and `.idea/mcp.xml`.

## Running it

```bash
export GITHUB_TOKEN=ghp_your_token_here          # repo, read:discussion, write:discussion scopes
export GITHUB_WEBHOOK_SECRET=your_webhook_secret  # optional locally; required in production
./scripts/run.sh
```

This builds and starts the server on `http://localhost:8080`:
- MCP endpoint: `http://localhost:8080/mcp/sse`
- Webhook: `http://localhost:8080/webhook/github`
- Manual trigger (no real webhook needed): `http://localhost:8080/webhook/trigger?owner=ORG&repo=REPO&pr=42`
- Swagger UI: `http://localhost:8080/swagger-ui`

Point your GitHub repo/org webhook at the `/webhook/github` URL (events: `pull_request_review`, `pull_request_review_comment`), or wire up [`.github/workflows/notify-bridge.yml`](.github/workflows/notify-bridge.yml) to push events to it from Actions instead of a raw webhook.

Docker: `docker compose -f docker/docker-compose.yml up --build`.

## Testing

```bash
./mvnw verify
```

34 tests, ~60% instruction/line coverage (JaCoCo report at `target/site/jacoco/index.html` after running `verify`). CI runs the same command on every push/PR via [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

Coverage focuses on the highest-risk paths: HMAC signature verification (valid/tampered/wrong-secret/malformed), webhook routing and dispatch (`@QuarkusTest` + RestAssured, real HTTP round-trips), GraphQL thread parsing including pagination and the null-vs-missing-node GraphQL error case, REST client commit/optimistic-concurrency behavior, and the shared retry/backoff/rate-limit logic. `PRReviewTools` (thin JSON marshalling over the already-tested clients) and the model records are not separately covered — a reasonable, disclosed gap rather than padding.

## Known limitations

- **Whole-file fixes, not diffs.** `apply_fix` / `auto_resolve_all` require the agent to submit the *entire* file content, not a patch. This was a deliberate tradeoff, not an oversight: implementing diff/patch application correctly (context matching, fuzzy hunks, conflict handling) is significantly more complex, and whole-file replacement is simpler and less error-prone for a first version. The cost is real, though — large files make every fix expensive in tokens, and there's real risk of an agent silently truncating or dropping unrelated content it wasn't supposed to touch. A diff-based version would send `apply_fix` a unified diff, validate that it applies cleanly against the current file `sha` fetched via `get_file_content`, and reject (rather than silently mis-apply) on a context mismatch.
- **In-memory queue.** `PRReviewOrchestrator` holds events in a bounded in-process deque — a restart drops pending events. Fine for a single-instance bridge; would need a real queue (SQS, Redis stream, etc.) to run more than one replica or survive restarts.
- **No persistence of resolution history.** Once a thread is resolved there's no audit trail beyond GitHub's own UI and the server logs.
