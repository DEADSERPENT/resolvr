# Architecture

```
GitHub  --webhook-->  GitHubWebhookResource  --enqueue-->  PRReviewOrchestrator (in-memory queue)
                                                                     |
IDE Agent (Copilot/Claude)  <--MCP/SSE-->  PRReviewTools  <---------+
                                                 |
                                                 +--> GitHubGraphQLClient  (review threads, resolve)
                                                 +--> GitHubRestClient    (read/commit files)
```

## Components

- **`GitHubWebhookResource`** — REST endpoint (`POST /webhook/github`) that verifies the HMAC-SHA256 signature, parses the event, and enqueues it. Also exposes `POST /webhook/trigger` for manual testing without a real webhook, and `GET /webhook/status` for queue depth.
- **`WebhookSignatureVerifier`** — constant-time HMAC-SHA256 verification against `X-Hub-Signature-256`. Skips verification only if no secret is configured (dev mode).
- **`PRReviewOrchestrator`** — bounded in-memory queue (last 500 events) of pending review activity. Drained by the MCP tool layer.
- **`GitHubGraphQLClient`** — fetches unresolved review threads (with cursor pagination past GitHub's 100-node page limit) and resolves threads via `resolveReviewThread`.
- **`GitHubRestClient`** — reads file content and commits fixes via the Contents API, using the current file `sha` for optimistic concurrency. Rejects oversized files client-side (GitHub's Contents API caps at ~1MB) and surfaces stale-sha conflicts (HTTP 409, i.e. the file changed since it was last read) with an actionable message instead of a raw API error.
- **`RetryingHttpSender`** — shared retry/backoff/rate-limit handling for both GitHub clients: exponential backoff on 5xx and transient I/O failures, honors `Retry-After` / `X-RateLimit-Reset` on 429/403, and aborts fast (rather than blocking) if a wait would exceed 30s.
- **`ApiKeyAuthFilter`** — global request gate requiring `Authorization: Bearer <RESOLVR_API_KEY>` on every route except `/webhook/github` (HMAC-verified separately) and `/q/health`. See [SECURITY.md](SECURITY.md).
- **`PendingFixStore`** — in-memory staging area used when `RESOLVR_REQUIRE_CONFIRMATION=true`; holds fixes until an explicit `confirm_fix` call.
- **`PRReviewTools`** — the MCP tool surface. See [TOOLS.md](TOOLS.md) for the full list.

## Why this exists

GitHub's hosted Copilot code reviewer can read a PR and leave comments on it, but it cannot *act* — it has no way to connect to an MCP server, run tools, or commit a fix. Only IDE-side agents (VS Code / JetBrains Copilot Agent Mode, Claude Code, etc.) can hold an MCP connection and actually do work. That split is the whole reason this project exists: something has to sit between "Copilot left review comments on GitHub" and "an agent with MCP tools and edit/commit access is sitting in your editor," and forward one to the other.

Resolvr is that bridge. It listens for GitHub webhook events and queues them, then exposes an MCP tool surface that an IDE agent polls to discover pending reviews, fetch unresolved thread context, read files, commit fixes, and resolve threads — all against the real GitHub REST + GraphQL APIs.
