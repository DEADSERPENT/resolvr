# Testing

```bash
./mvnw verify
```

53 tests, ~71% instruction / 70% line coverage (JaCoCo report at `target/site/jacoco/index.html` after running `verify`). CI runs the same command on every push/PR via [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

The model records are the main uncovered surface — thin data holders, low risk, a reasonable disclosed gap rather than padding.

## Scenario coverage: best / good / worst case

Rather than just "does the happy path work," the suite (and a live end-to-end run against a real throwaway GitHub repo/PR) deliberately covers three tiers:

**Best case — happy path**
- Webhook arrives with a valid signature → event queued → agent fetches unresolved threads → applies a fix → resolves the thread. Covered by `GitHubWebhookResourceTest`, `GitHubGraphQLClientTest`, `GitHubRestClientTest`, and validated live: a real PR was opened against `DEADSERPENT/resolvr-e2e-test`, a real review comment created a real unresolved thread, and the exact GraphQL/REST calls this code makes fetched it, committed a fix, and resolved it on live GitHub.

**Good case — transient trouble that should self-heal**
- GitHub returns a 5xx or the connection drops → retried with exponential backoff, succeeds on a later attempt (`RetryingHttpSenderTest`).
- GitHub rate-limits the request (429/403) → the `Retry-After` / `X-RateLimit-Reset` header is honored and the call is retried once the window passes, as long as the wait is short; a long wait aborts fast instead of blocking (`RetryingHttpSenderTest`).
- A PR has more than 100 review threads → cursor pagination walks every page (`GitHubGraphQLClientTest`).
- `auto_resolve_all` partially fails (one fix commits, another errors) → per-fix results are reported individually rather than the whole batch failing opaquely (`PRReviewToolsConfirmationTest`).

**Worst case — real failure modes, handled explicitly rather than surfaced as raw errors**
- The file changed on GitHub since it was last read (someone else pushed a commit between `get_file_content` and `apply_fix`) → GitHub returns HTTP 409, and the client turns that into "the file changed since it was last read — call get_file_content again" instead of a bare API error (`GitHubRestClientTest`). This was also reproduced live: a concurrent commit was pushed to the real test branch, and a follow-up commit using the now-stale sha reliably got a 409 back from GitHub, confirming the assumption the handling is built on.
- A fix is larger than GitHub's Contents API can accept (~1MB) → rejected client-side before any network call, with a clear message, instead of a confusing GitHub error after the fact (`GitHubRestClientTest`).
- GraphQL returns `pullRequest: null` alongside an `errors` array (e.g. a bad PR number) → this is a `NullNode`, not a "missing" field, and naive missing-node checks silently swallow it; explicitly checked and surfaced as an error instead (`GitHubGraphQLClientTest`).
- The GitHub API is down entirely (persistent 5xx) → retried up to the limit, then fails loudly rather than hanging or silently dropping the operation (`RetryingHttpSenderTest`).
- An unauthenticated or wrong-key request hits any protected route → rejected with 401 before reaching any business logic (`ApiKeyAuthFilterTest`).
- A malformed webhook payload arrives with a valid signature → parsed, fails cleanly with 400, doesn't crash the queue (`GitHubWebhookResourceTest`).
