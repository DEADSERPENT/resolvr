# MCP tools

| Tool | Purpose |
|---|---|
| `get_workspace_pr_context` | Default/on-demand discovery — resolves workspace → Git remote → owner/repo → branch → matching open PR, with local-vs-PR HEAD sync info. Call this first; no webhook needed. |
| `get_current_pr` | Thin wrapper around `get_workspace_pr_context` returning just repository + pullRequest |
| `get_pr_context` | PR Context Engine — full structured context for the current PR in one call: metadata, review threads (resolved + unresolved), comments, changed files, diff, commits, and CI status. Builds on `get_workspace_pr_context`. |
| `get_local_changes` | Read-only — what's changed in the local working tree right now, from `git status`/`git diff`, not from asking the agent |
| `prepare_resolution_summary` | Read-only — builds and stages an approval package (branch/HEAD/PR-state verified) for the current local changes; returns a token |
| `commit_and_push_resolution` | **The approval boundary** — only call after explicit developer approval. Re-verifies everything independently, then commits exactly the approved files and pushes |
| `resolve_addressed_threads` | Resolves review threads on GitHub — only usable after a successful `commit_and_push_resolution` |
| `discard_resolution` | Cancels a prepared resolution without committing or pushing anything |
| `poll_pending_reviews` | Webhook mode only — discover PRs with new Copilot review activity queued by an inbound webhook |
| `fetch_pr_comments` | Get all unresolved threads for a PR, with prompt-ready context |
| `get_file_content` | Read the current state of a file from the PR branch |
| `apply_fix` | Commit a full-file fix to the PR branch (or stage it — see [SECURITY.md](SECURITY.md)) |
| `resolve_thread` | Mark one thread resolved |
| `resolve_all_threads` | Batch-resolve multiple threads |
| `auto_resolve_all` | Commit fixes + resolve threads in one call (or stage them — see [SECURITY.md](SECURITY.md)) |
| `list_pending_fixes` | Confirmation mode only — preview what's staged before committing |
| `confirm_fix` | Confirmation mode only — commit one staged fix by its token |
| `confirm_all_pending_fixes` | Confirmation mode only — commit every staged fix at once |
| `discard_pending_fix` | Confirmation mode only — cancel a staged fix without committing it |

IDE agent instructions for using these tools live in [`.github/copilot-instructions.md`](../.github/copilot-instructions.md). VS Code and JetBrains MCP client config are checked in at `.vscode/mcp.json` and `.idea/mcp.xml`.
