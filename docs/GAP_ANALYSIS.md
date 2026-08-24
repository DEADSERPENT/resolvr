# Resolvr — Gap Analysis vs. Revised Specification (2026-08-24)

Compares the current implementation (`src/main/java/com/resolvr/...`) against
[`Resolvr_Final_Product_Development_Specification.md`](../Resolvr_Final_Product_Development_Specification.md)
as revised to make Manual/On-demand (§6.1 Mode 1) the default architecture and
webhooks (§20) optional. No code has been changed — this is analysis only.

## Headline gap

The current implementation is **entirely webhook-driven** and **remote-first**.
The revised spec's default path is **on-demand** (Copilot asks, Resolvr
queries GitHub live) and **local-first** (Copilot Agent edits the workspace;
Resolvr only handles GitHub read + final commit/push). Neither of those two
defining properties of the new default mode exists today:

- There is no `get_current_pr` / `get_workspace_pr_context` tool and no
  workspace→Git-remote→branch→PR discovery logic anywhere in the codebase
  (confirmed: no `git rev-parse`, `git remote`, or `git branch` invocation
  exists in `src/main/java`). `poll_pending_reviews`
  (`PRReviewTools.java:64`) only drains whatever the webhook already queued
  in `PendingFixStore` — it cannot discover a PR on its own.
- `apply_fix` (`PRReviewTools.java:164`) takes a **complete new file
  content** string and commits it straight to GitHub via the Contents API
  (§10, §38 — the "AI → GitHub Contents API → remote commit" pattern the spec
  now explicitly discourages as the default). There is no code path where the
  agent edits a file in the local workspace and Resolvr just validates/
  commits/pushes that local change.

Everything else in the current codebase (GitHub REST/GraphQL clients, HMAC
webhook verification, MCP/SSE server, stage/confirm/discard workflow, API-key
auth, retry/backoff, optimistic concurrency via file SHA, CI, Docker, tests)
maps cleanly onto the **optional** Webhook mode (§6.1 Mode 3, §20) and onto
GitHub-side plumbing the on-demand mode will still need (§37 permissions,
§39 error handling, §22 dedup). None of it needs to be thrown away — see
Appendix A of the spec.

## Status by revised build order (spec §44)

| # | Item | Status | Evidence / gap |
|---|------|--------|-----------------|
| 1 | Current workspace → current PR discovery (§9, §9.1) | **Missing** | No `get_current_pr`/`get_workspace_pr_context` tool; no local Git introspection code at all. |
| 2 | PR context retrieval (§8) | **Partial** | `fetchPRComments` (`PRReviewTools.java:89`) returns comments/threads via `GitHubGraphQLClient`, but nothing assembles the full structured context object (diff, CI status, changed files, commit history) the spec describes in §8. |
| 3 | Review-thread retrieval | **Done** | `GitHubGraphQLClient` + `fetchPRComments`/`ReviewThread` model already do this, cursor-paginated past 100 nodes per README. |
| 4 | Copilot MCP workflow | **Partial** | MCP/SSE server exists and exposes 11 tools (`PRReviewTools.java`), but the tool set assumes the webhook-first flow, not on-demand discovery. |
| 5 | Local-agent resolution (local-first editing) | **Missing** | `apply_fix` is remote-first (full-file content → Contents API commit), not "agent edits workspace file, Resolvr commits what's on disk." |
| 6 | Validation (run tests) | **Missing** | No code invokes `mvnw test`/`npm test`/etc. anywhere; §26 is unimplemented. |
| 7 | Approval gate | **Done** | `RESOLVR_REQUIRE_CONFIRMATION`, `PendingFixStore`, `confirm_fix`/`confirm_all_pending_fixes`/`discard_pending_fix` implement §15–16 well. |
| 8 | Commit/push | **Partial** | Commit-via-Contents-API exists (`apply_fix`, `auto_resolve_all`); there's no "commit/push what's already in the local working tree" path, and no branch-safety or dirty-tree checks (§24, §25). |
| 9 | Thread resolution | **Done** | `resolve_thread`/`resolve_all_threads` via GraphQL mutation, only called after commit succeeds — matches §17. |
| 10 | CI loop (§19, §32) | **Missing** | No `get_ci_status` tool, no CI-log fetch, no local-reproduce workflow. |
| 11 | Persistence (§21) | **Not started (expected)** | `PendingFixStore` is in-memory, matches spec's MVP recommendation; SQLite is a later phase. |
| 12 | GitHub App (§37) | **Not started (expected)** | Still PAT/`GITHUB_TOKEN`-based via `GitHubTokenResolver`; spec explicitly defers this. |
| 13 | Webhook/polling mode (optional, §6.1, §20) | **Done (as the only mode)** | `GitHubWebhookResource`, `WebhookSignatureVerifier` (HMAC-SHA256), `PRReviewOrchestrator` fully implement Mode 3. This is currently load-bearing for the *entire* product, whereas the revised spec wants it to be optional and additive once Mode 1 exists. |

## Safety/error-handling gaps called out by the spec (§24, §25, §39)

Not implemented anywhere in `src/main/java`:
- Branch safety — checking current branch vs. PR head branch vs. base branch before any write (§24).
- Dirty working tree detection before touching local files (§25).
- Stale-branch / stale-file distinct error messaging beyond the SHA-conflict case already handled for the Contents API path (§39 examples like "current branch does not match PR head branch").

These matter more once local-first editing (item 5 above) exists, since that's when Resolvr starts reasoning about the actual local working tree instead of only the GitHub-side file SHA.

## What's already solid and should not be touched

Per spec §45 and confirmed in code: `GitHubRestClient`, `GitHubGraphQLClient`, `WebhookSignatureVerifier`, `RetryingHttpSender` (retry/backoff + rate-limit handling), `ApiKeyAuthFilter`, the confirm/stage/discard workflow in `PendingFixStore`/`PRReviewTools`, optimistic concurrency via file SHA in `apply_fix`, CI workflow, Docker/GHCR, and the existing 53-test suite. All of it is reused as-is under the revised architecture — Mode 3 plumbing, not dead weight.

## Recommended next concrete step

Implement **`get_workspace_pr_context`** (spec §9.1) as a new MCP tool:
workspace path → `git remote get-url origin` → parse `owner/repo` →
`git branch --show-current` → list open PRs via existing
`GitHubGraphQLClient`/`GitHubRestClient` → match `head.ref` → return the
structured JSON shape in §9.1. This unblocks Phase 1 of the revised spec and
requires no webhook, no new persistence, and no changes to the existing
GitHub clients' public surface — it's a new orchestration class plus one new
`@Tool` method.
