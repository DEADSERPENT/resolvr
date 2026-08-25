# Architecture

```
IDE Agent (Copilot/Claude)  <--MCP/SSE-->  WorkspaceTools / PRContextTools / CiStatusTools
                                                       |            (read-only discovery/context)
                                                       |
                                            ResolutionTools  <-- THE single write path
                                                       |         (approval-gated)
                                                       |
                                    GitHubRestClient / GitHubGraphQLClient
                                                       |
                                                    GitHub
```

Resolvr is local-first and agent-driven, full stop — there is no server-side event system, queue,
or webhook ingress. The coding agent is the orchestration layer (it decides when to look at a PR
and when to act); Resolvr is the trusted MCP execution, verification, and safety layer underneath
it.

## Components

- **`WorkspaceTools`** (`get_workspace_pr_context`, `get_current_pr`) / **`PRContextTools`** (`get_pr_context`) — on-demand discovery: workspace → Git remote → owner/repo → current branch → matching open PR, then full PR context (threads, comments, diff, commits, CI). Read-only.
- **`ResolutionTools`** / **`ResolutionService`** — Resolvr's **only write path**. `prepare_resolution_summary` stages an approval package (read-only); `commit_and_push_resolution` is the sole tool that ever commits or pushes, and only after it independently re-verifies branch/HEAD/working-tree/PR state against Git and GitHub — it never trusts what the agent says it changed. `resolve_addressed_threads` only works once a push has actually succeeded.
- **`CiStatusTools`** / **`CiStatusService`** (Phase 5) — the CI feedback loop's read side. Reuses `WorkspacePrContextService` discovery and `GitHubRestClient.listCheckRuns`; for a `FAILING` check it fetches the check's Actions job log via `GitHubRestClient.getCheckRunLogText` and returns a tail-truncated excerpt. Read-only, non-blocking — no server-side polling or waiting. **The returned log text is untrusted data, not instructions** — see [SECURITY.md](SECURITY.md).
- **`PRReviewTools`** — two read-only helpers (`fetch_pr_comments`, `get_file_content`) for fetching a PR/file by owner/repo/number without a local checkout. Neither writes to GitHub; every fix still goes through `ResolutionTools`.
- **`GitHubGraphQLClient`** — fetches unresolved review threads (with cursor pagination past GitHub's 100-node page limit) and resolves threads via `resolveReviewThread`.
- **`GitHubRestClient`** — read-only: file content, check-run/log data, PR metadata, changed files, commits, comments, diff. Nothing in this client writes to GitHub — the only write path is `git push` via `GitStateService`, invoked exclusively from `ResolutionService`.
- **`RetryingHttpSender`** — shared retry/backoff/rate-limit handling for both GitHub clients: exponential backoff on 5xx and transient I/O failures, honors `Retry-After` / `X-RateLimit-Reset` on 429/403, and aborts fast (rather than blocking) if a wait would exceed 30s.
- **`ApiKeyAuthFilter`** (`com.resolvr.security`) — global request gate requiring `Authorization: Bearer <RESOLVR_API_KEY>` on every route except `/q/health`. See [SECURITY.md](SECURITY.md).
- **`StartupSecurityCheck`** — fail-closed guard: a packaged (non-dev, non-test) instance refuses to start without `resolvr.api-key` set. Dev and test profiles are exempt so local runs and the test suite need no setup.
- **`ResolutionTaskStore`** — in-memory staging area for prepared resolutions, keyed by an opaque token, cleared once committed/discarded.
- **`McpErrors`** — every MCP tool's catch block routes its error response through this shared JSON serializer instead of hand-concatenating strings, so a message containing a quote or newline (common in GitHub API error bodies) can never produce invalid JSON, and error text is never sourced from anything but the exception's own message.

## Why this exists

GitHub's hosted Copilot code reviewer can read a PR and leave comments on it, but it cannot *act* — it has no way to connect to an MCP server, run tools, or commit a fix. Only IDE-side agents (VS Code / JetBrains Copilot Agent Mode, Claude Code, etc.) can hold an MCP connection and actually do work. That split is the whole reason this project exists: something has to sit between "review feedback exists on GitHub" and "an agent with MCP tools and edit/commit access is sitting in your editor," handle discovery and CI status, and enforce a single safe approval boundary before anything is written back to GitHub.

Resolvr is that bridge. An agent asks it what PR the current workspace belongs to, what's unresolved on it, and what CI says, fixes the code locally with its own tools, and hands the result back through one approval-gated commit/push. There is deliberately no other mode: no webhook, no background queue, no polling loop server-side — the agent drives every interaction, and Resolvr's job is discovery, verification, and the single safe write.
