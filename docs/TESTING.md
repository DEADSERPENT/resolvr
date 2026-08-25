# Testing

```bash
./mvnw verify
```

168 tests, ~79% instruction / 77% line coverage (JaCoCo report at `target/site/jacoco/index.html` after running `verify`). CI runs the same command on every push/PR via [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

The model records are the main uncovered surface — thin data holders, low risk, a reasonable disclosed gap rather than padding.

## Scenario coverage: best / good / worst case

Rather than just "does the happy path work," the suite (and a live end-to-end run against a real throwaway GitHub repo/PR) deliberately covers three tiers:

**Best case — happy path**
- Local-first flow end to end: an agent's local edit is discovered (`get_local_changes`), staged (`prepare_resolution_summary`, verifying branch/HEAD/PR-sync against a real temp Git repo), committed and pushed to a real local bare-repo "origin" (`commit_and_push_resolution`), and its threads resolved (`resolve_addressed_threads`) — `ResolutionServiceTest`.
- Fail-closed startup: a packaged instance with no `resolvr.api-key` refuses to boot rather than starting open (`StartupSecurityCheckTest`).

**Good case — transient trouble that should self-heal**
- GitHub returns a 5xx or the connection drops → retried with exponential backoff, succeeds on a later attempt (`RetryingHttpSenderTest`).
- GitHub rate-limits the request (429/403) → the `Retry-After` / `X-RateLimit-Reset` header is honored and the call is retried once the window passes, as long as the wait is short; a long wait aborts fast instead of blocking (`RetryingHttpSenderTest`).
- A PR has more than 100 review threads → cursor pagination walks every page (`GitHubGraphQLClientTest`).
- `resolve_addressed_threads` partially fails (one thread resolves, another errors) → per-thread results are reported individually rather than the whole batch failing opaquely (`ResolutionServiceTest.resolveThreads_partialFailure_reportsPerThreadResults`).
- CI status flips from `PENDING` to `FAILING` across two `get_ci_status` polls, as it would while an agent waits between calls (`CiStatusServiceTest`).

**Worst case — real failure modes, handled explicitly rather than surfaced as raw errors**
- The PR's remote HEAD moved on GitHub since `prepare_resolution_summary` staged the approval, or the PR closed in the meantime → `commit_and_push_resolution` re-fetches PR state immediately before writing and refuses with a clear "re-run prepare_resolution_summary" error rather than pushing against stale assumptions (`ResolutionServiceTest`).
- GraphQL returns `pullRequest: null` alongside an `errors` array (e.g. a bad PR number) → this is a `NullNode`, not a "missing" field, and naive missing-node checks silently swallow it; explicitly checked and surfaced as an error instead (`GitHubGraphQLClientTest`).
- The GitHub API is down entirely (persistent 5xx) → retried up to the limit, then fails loudly rather than hanging or silently dropping the operation (`RetryingHttpSenderTest`).
- An unauthenticated or wrong-key request hits any protected route → rejected with 401 before reaching any business logic (`ApiKeyAuthFilterTest`).
- A failing check's log comes from a non-Actions CI app (job-logs endpoint 404s) → reported as `logAvailable: false` with a fallback `htmlUrl`, not an exception (`GitHubRestClientTest`, `CiStatusServiceTest`).
- A failing check's log is longer than the configured tail-line/byte cap → excerpt is truncated with `truncated: true` and the original line count, not silently cut or sent whole (`CiStatusServiceTest`).
- `listCheckRuns` itself fails while polling CI status (GitHub down) → `error` surfaced on the `ci` section rather than fabricated, matching `get_pr_context`'s existing per-section failure handling (`CiStatusServiceTest`).
