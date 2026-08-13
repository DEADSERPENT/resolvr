# MCP tools

| Tool | Purpose |
|---|---|
| `poll_pending_reviews` | Discover PRs with new Copilot review activity |
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
