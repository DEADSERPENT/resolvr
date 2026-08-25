package com.resolvr.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resolvr.github.GitHubGraphQLClient;
import com.resolvr.github.GitHubRestClient;
import com.resolvr.model.ReviewThread;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Read-only PR review discovery helpers. Resolvr has exactly one write path —
 * ResolutionTools (get_local_changes → prepare_resolution_summary →
 * commit_and_push_resolution → resolve_addressed_threads), which re-verifies
 * Git/GitHub state and requires an explicit developer approval before every
 * push. The tools here exist only to fetch context for a PR by owner/repo/
 * number when it isn't checked out locally yet — neither writes to GitHub;
 * every fix still goes through the single approval boundary in
 * ResolutionTools.
 *
 * Once you have a local checkout, prefer get_workspace_pr_context /
 * get_pr_context (WorkspaceTools/PRContextTools) instead — they discover the
 * PR from the workspace directly and cover the same ground plus CI status,
 * diff, and commits in one call.
 */
@ApplicationScoped
public class PRReviewTools {

    @Inject GitHubGraphQLClient graphQL;
    @Inject GitHubRestClient rest;

    private final ObjectMapper mapper = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 1 — Fetch all unresolved PR review threads
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "fetch_pr_comments", description = """
            Read-only: fetch all UNRESOLVED GitHub review thread comments for a specific PR.
            Returns structured JSON with threadId, filePath, line, commentBody, and prBranch.
            get_pr_context (PRContextTools) covers this plus full PR metadata/diff/CI in one call for
            the current workspace's PR — prefer that for the local-first flow. This tool remains for
            fetching a specific PR by owner/repo/number without a local workspace checked out yet.
            """)
    public String fetchPRComments(
            @ToolArg(description = "GitHub repository owner (user or org name)") String owner,
            @ToolArg(description = "GitHub repository name (without owner)") String repo,
            @ToolArg(description = "Pull request number (integer)") int prNumber
    ) {
        try {
            List<ReviewThread> threads = graphQL.getUnresolvedThreads(owner, repo, prNumber);
            if (threads.isEmpty()) {
                return "{\"count\":0,\"threads\":[],\"message\":\"All review threads are already resolved.\"}";
            }

            // Build rich context for the IDE agent
            StringBuilder context = new StringBuilder();
            context.append("=== PR REVIEW CONTEXT: ").append(owner).append("/").append(repo)
                    .append(" #").append(prNumber).append(" ===\n\n");
            context.append("Unresolved threads: ").append(threads.size()).append("\n\n");
            for (int i = 0; i < threads.size(); i++) {
                context.append("--- Thread ").append(i + 1).append(" ---\n");
                context.append(threads.get(i).toPromptContext()).append("\n");
            }

            return mapper.writeValueAsString(Map.of(
                    "count", threads.size(),
                    "context", context.toString(),
                    "threads", threads
            ));
        } catch (Exception e) {
            Log.errorf(e, "fetchPRComments failed for %s/%s#%d", owner, repo, prNumber);
            return McpErrors.error(e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 2 — Read a file from the PR branch
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "get_file_content", description = """
            Read-only: the current content of a file from a branch on GitHub. Useful when inspecting a
            PR that isn't checked out locally yet; once on the PR's branch locally, prefer reading the
            file with your own editor/file tools instead. Returns the raw file content as a string.
            """)
    public String getFileContent(
            @ToolArg(description = "GitHub repository owner") String owner,
            @ToolArg(description = "GitHub repository name") String repo,
            @ToolArg(description = "Branch name (get this from fetchPRComments → threads[].prBranch)") String branch,
            @ToolArg(description = "File path relative to repo root (e.g. src/main/java/Foo.java)") String filePath
    ) {
        try {
            String content = rest.getFileContent(owner, repo, branch, filePath);
            return mapper.writeValueAsString(Map.of(
                    "filePath", filePath,
                    "branch", branch,
                    "content", content,
                    "lines", content.split("\n").length
            ));
        } catch (Exception e) {
            Log.errorf(e, "getFileContent failed: %s/%s/%s/%s", owner, repo, branch, filePath);
            return McpErrors.error(e.getMessage());
        }
    }
}
