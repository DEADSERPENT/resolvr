# Security

Two things gate this server beyond the webhook's own HMAC check, both off by default so a bare `./scripts/run.sh` on localhost keeps working with zero setup — **both should be turned on for anything that isn't purely local**:

- **`RESOLVR_API_KEY`** — without it, `/mcp/sse` and `/webhook/trigger` have no authentication at all: anyone who can reach the port can drive this server's GitHub token (read private files, commit to branches, resolve threads). Set it and every route except `/webhook/github` (HMAC-verified separately) and `/q/health` requires `Authorization: Bearer <key>`. `.vscode/mcp.json` is already wired to prompt for it via VS Code's secret input mechanism.
- **`RESOLVR_REQUIRE_CONFIRMATION=true`** — by default `apply_fix`/`auto_resolve_all` commit and resolve immediately, which is fine when you're the only one whose fixes ever land. The moment other people's repos or unreviewed agent output are involved, turn this on: fixes get staged instead of committed, and nothing reaches GitHub until an explicit `confirm_fix` / `confirm_all_pending_fixes` call — giving a human (or a second, reviewing agent) a checkpoint to catch a bad LLM-generated fix before it lands in someone's PR.

Neither of these makes this a multi-tenant service — it's still a single shared `GITHUB_TOKEN` for whatever repos that token can reach. Turning both on is what makes "self-hosted, shared with a team" reasonable; it does not make "hosted for strangers" reasonable — that would need a GitHub App instead of a PAT, and per-user token scoping this project doesn't have.

## GitHub token permissions

If you're using a fine-grained personal access token, two things trip people up:

- **Repository access and content permissions are separate settings.** Selecting a repo under "Repository access" is not enough — you also need to grant **Contents: Read and write** and **Pull requests: Read and write** under that token's permissions, or every write call 403s with "Resource not accessible by personal access token" even though the repo itself is selected.
- **Fine-grained PATs cannot create new repositories via the API.** This is a GitHub platform limitation, not a permission you can grant. If you need Resolvr (or anything using this token) to create a repo, use a classic PAT with the `repo` scope instead, or create the repo manually first.
