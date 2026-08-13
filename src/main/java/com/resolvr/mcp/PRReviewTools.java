package com.resolvr.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resolvr.github.GitHubGraphQLClient;
import com.resolvr.github.GitHubRestClient;
import com.resolvr.model.FixResult;
import com.resolvr.model.PRReviewEvent;
import com.resolvr.model.PendingFix;
import com.resolvr.model.ReviewThread;
import com.resolvr.orchestrator.PRReviewOrchestrator;
import com.resolvr.orchestrator.PendingFixStore;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools exposed to IDE Copilot (VS Code / JetBrains Agent Mode).
 *
 * Typical agent workflow:
 *   1. poll_pending_reviews        → discover PRs needing attention
 *   2. fetch_pr_comments           → get all unresolved threads + context
 *   3. get_file_content            → read the file to understand the fix scope
 *   4. apply_fix                   → commit the fix to the PR branch
 *   5. resolve_thread              → mark the thread resolved on GitHub
 *
 * Or use auto_resolve_all for a one-shot orchestration call.
 *
 * When resolvr.require-confirmation=true, apply_fix and auto_resolve_all stage
 * fixes instead of committing them — nothing reaches GitHub until confirm_fix
 * (or confirm_all_pending_fixes) is called explicitly. Use list_pending_fixes
 * to review what's staged first.
 */
@ApplicationScoped
public class PRReviewTools {

    @Inject GitHubGraphQLClient graphQL;
    @Inject GitHubRestClient rest;
    @Inject PRReviewOrchestrator orchestrator;
    @Inject PendingFixStore pendingFixes;

    @ConfigProperty(name = "resolvr.require-confirmation", defaultValue = "false")
    boolean requireConfirmation;

    private final ObjectMapper mapper = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 1 — Poll pending review events (from GitHub webhooks)
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            Check if GitHub has sent any new PR review events via webhook.
            Returns a list of PRs that have new Copilot review comments and need attention.
            Call this first at the start of each agent session to discover work.
            Returns JSON array of {owner, repo, prNumber, action, receivedAt}.
            """)
    public String pollPendingReviews() {
        List<PRReviewEvent> events = orchestrator.drainAll();
        if (events.isEmpty()) {
            return "{\"pending\":0,\"events\":[],\"message\":\"No new review events. Trigger manually via POST /webhook/trigger or raise a PR.\"}";
        }
        try {
            return mapper.writeValueAsString(Map.of(
                    "pending", events.size(),
                    "events", events
            ));
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 2 — Fetch all unresolved PR review threads
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            Fetch all UNRESOLVED GitHub Copilot review thread comments for a specific PR.
            Returns structured JSON with threadId, filePath, line, commentBody, and prBranch.
            Use threadId when calling resolve_thread later.
            Use filePath + prBranch when calling get_file_content.
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
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 3 — Read a file from the PR branch
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            Read the current content of a file from the PR branch on GitHub.
            Always call this BEFORE applying a fix so you see the exact current state.
            Returns the raw file content as a string.
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
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 4 — Commit a fix to the PR branch
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            Commit a code fix directly to the PR branch on GitHub.
            Provide the COMPLETE new file content (not a diff — the full file).
            The commit message should reference the thread being fixed.
            Returns the commit SHA on success.
            IMPORTANT: Always call get_file_content first to get the current content,
            then modify only the relevant lines, then call this tool with the full updated file.
            If the server has resolvr.require-confirmation enabled, this stages the fix
            instead of committing it — call confirm_fix with the returned token to commit.
            """)
    public String applyFix(
            @ToolArg(description = "GitHub repository owner") String owner,
            @ToolArg(description = "GitHub repository name") String repo,
            @ToolArg(description = "Branch name (from fetchPRComments → threads[].prBranch)") String branch,
            @ToolArg(description = "File path relative to repo root") String filePath,
            @ToolArg(description = "Complete new file content (entire file, not just the changed lines)") String newContent,
            @ToolArg(description = "Commit message — be descriptive, e.g. 'fix: address Copilot review comment on null check in Foo.java'") String commitMessage
    ) {
        if (requireConfirmation) {
            String token = pendingFixes.stage(owner, repo, branch, filePath, newContent, commitMessage, null);
            try {
                return mapper.writeValueAsString(Map.of(
                        "staged", true,
                        "token", token,
                        "filePath", filePath,
                        "branch", branch,
                        "message", "Fix staged, not yet committed. Call confirm_fix(token) to commit it, "
                                + "or discard_pending_fix(token) to cancel."
                ));
            } catch (Exception e) {
                return "{\"staged\":true,\"token\":\"" + token + "\"}";
            }
        }
        try {
            String sha = rest.commitFileChange(owner, repo, branch, filePath, newContent, commitMessage);
            return mapper.writeValueAsString(Map.of(
                    "success", true,
                    "commitSha", sha,
                    "filePath", filePath,
                    "branch", branch,
                    "message", "Fix committed. Now call resolve_thread with the threadId."
            ));
        } catch (Exception e) {
            Log.errorf(e, "applyFix failed for %s", filePath);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 5 — Resolve a single review thread
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            Mark a specific GitHub review thread as resolved.
            Call this AFTER successfully applying the fix for that thread.
            The threadId comes from fetchPRComments → threads[].threadId.
            """)
    public String resolveThread(
            @ToolArg(description = "GitHub thread node ID (from fetchPRComments → threads[].threadId)") String threadId
    ) {
        try {
            graphQL.resolveThread(threadId);
            return "{\"resolved\":true,\"threadId\":\"" + threadId + "\"}";
        } catch (Exception e) {
            Log.errorf(e, "resolveThread failed: %s", threadId);
            return "{\"resolved\":false,\"threadId\":\"" + threadId + "\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 6 — Batch resolve multiple threads at once
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            Resolve multiple GitHub review threads in one call.
            Use this after applying all fixes to close out all threads efficiently.
            threadIds is a JSON array of thread node ID strings.
            Example: ["RT_kwDOA...1", "RT_kwDOA...2"]
            """)
    public String resolveAllThreads(
            @ToolArg(description = "JSON array of thread node IDs to resolve") String threadIdsJson
    ) {
        try {
            List<String> ids = mapper.readValue(threadIdsJson, new TypeReference<List<String>>() {});
            List<Map<String, Object>> results = new ArrayList<>();
            for (String id : ids) {
                try {
                    graphQL.resolveThread(id);
                    results.add(Map.of("threadId", id, "resolved", true));
                } catch (Exception e) {
                    results.add(Map.of("threadId", id, "resolved", false, "error", e.getMessage()));
                }
            }
            long resolved = results.stream().filter(r -> Boolean.TRUE.equals(r.get("resolved"))).count();
            return mapper.writeValueAsString(Map.of(
                    "resolvedCount", resolved,
                    "totalCount", ids.size(),
                    "results", results
            ));
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 7 — All-in-one auto-resolve (apply fixes + resolve in one structured call)
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            POWER TOOL: Apply multiple fixes and resolve their threads in one efficient call.
            Use this after you have generated all the fixed file contents.

            fixesJson is a JSON array of objects, each with:
              - threadId   : the GitHub thread node ID to resolve after the fix
              - filePath   : relative file path in the repo
              - newContent : complete new file content
              - commitMessage : descriptive commit message

            Example:
            [
              {
                "threadId": "RT_kwDO...",
                "filePath": "src/Foo.java",
                "newContent": "...full file...",
                "commitMessage": "fix: null check per Copilot review"
              }
            ]

            Returns a summary of what succeeded and what failed.
            If the server has resolvr.require-confirmation enabled, this stages every fix
            instead of committing them — call confirm_all_pending_fixes (or confirm_fix per
            token) to actually commit and resolve.
            """)
    public String autoResolveAll(
            @ToolArg(description = "GitHub repository owner") String owner,
            @ToolArg(description = "GitHub repository name") String repo,
            @ToolArg(description = "PR branch name") String branch,
            @ToolArg(description = "JSON array of fix objects [{threadId, filePath, newContent, commitMessage}]") String fixesJson
    ) {
        try {
            List<Map<String, String>> fixes = mapper.readValue(fixesJson,
                    new TypeReference<List<Map<String, String>>>() {});

            if (requireConfirmation) {
                List<Map<String, String>> staged = new ArrayList<>();
                for (Map<String, String> fix : fixes) {
                    String threadId = fix.get("threadId");
                    String filePath = fix.get("filePath");
                    String newContent = fix.get("newContent");
                    String commitMsg = fix.getOrDefault("commitMessage",
                            "fix: address Copilot review comment in " + filePath);
                    String token = pendingFixes.stage(owner, repo, branch, filePath, newContent, commitMsg, threadId);
                    staged.add(Map.of("token", token, "filePath", filePath, "threadId", threadId));
                }
                return mapper.writeValueAsString(Map.of(
                        "staged", true,
                        "count", staged.size(),
                        "fixes", staged,
                        "message", staged.size() + " fixes staged, not yet committed. Review with "
                                + "list_pending_fixes, then call confirm_all_pending_fixes to commit them all."
                ));
            }

            List<FixResult> results = new ArrayList<>();

            for (Map<String, String> fix : fixes) {
                String threadId = fix.get("threadId");
                String filePath = fix.get("filePath");
                String newContent = fix.get("newContent");
                String commitMsg = fix.getOrDefault("commitMessage",
                        "fix: address Copilot review comment in " + filePath);

                try {
                    // 1. Commit the fix
                    String sha = rest.commitFileChange(owner, repo, branch, filePath, newContent, commitMsg);
                    // 2. Resolve the thread
                    graphQL.resolveThread(threadId);
                    results.add(FixResult.success(threadId, filePath, sha));
                    Log.infof("auto-resolved: %s → commit %s", filePath, sha);
                } catch (Exception e) {
                    Log.errorf(e, "auto-resolve failed for thread %s", threadId);
                    results.add(FixResult.failure(threadId, filePath, e.getMessage()));
                }
            }

            long succeeded = results.stream().filter(FixResult::resolved).count();
            return mapper.writeValueAsString(Map.of(
                    "summary", succeeded + "/" + results.size() + " fixes applied and threads resolved",
                    "results", results
            ));
        } catch (Exception e) {
            Log.errorf(e, "autoResolveAll failed");
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 8 — List fixes staged for confirmation
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            List every fix currently staged for review (only relevant when
            resolvr.require-confirmation is enabled). Returns a preview of each
            pending fix — token, target file, commit message, and content length —
            without dumping the full file content.
            """)
    public String listPendingFixes() {
        try {
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (PendingFix fix : pendingFixes.listAll()) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("token", fix.token());
                summary.put("owner", fix.owner());
                summary.put("repo", fix.repo());
                summary.put("branch", fix.branch());
                summary.put("filePath", fix.filePath());
                summary.put("commitMessage", fix.commitMessage());
                summary.put("threadId", fix.threadId());
                summary.put("contentLength", fix.newContent().length());
                summary.put("createdAt", fix.createdAt().toString());
                summaries.add(summary);
            }
            return mapper.writeValueAsString(Map.of("count", summaries.size(), "pending", summaries));
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 9 — Confirm and commit one staged fix
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            Commit a fix that was previously staged by apply_fix or auto_resolve_all
            (only relevant when resolvr.require-confirmation is enabled).
            Commits the file, resolves the associated thread if one was staged, and
            removes the fix from the pending list. Use list_pending_fixes to find tokens.
            """)
    public String confirmFix(
            @ToolArg(description = "Token returned by apply_fix or auto_resolve_all when staged") String token
    ) {
        PendingFix fix = pendingFixes.get(token);
        if (fix == null) {
            return "{\"success\":false,\"error\":\"no pending fix with that token — "
                    + "already confirmed, discarded, or invalid\"}";
        }
        try {
            String sha = rest.commitFileChange(fix.owner(), fix.repo(), fix.branch(),
                    fix.filePath(), fix.newContent(), fix.commitMessage());
            if (fix.threadId() != null) {
                graphQL.resolveThread(fix.threadId());
            }
            pendingFixes.remove(token);
            return mapper.writeValueAsString(Map.of(
                    "success", true,
                    "commitSha", sha,
                    "filePath", fix.filePath(),
                    "threadResolved", fix.threadId() != null
            ));
        } catch (Exception e) {
            Log.errorf(e, "confirmFix failed for token %s", token);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 10 — Confirm and commit every staged fix
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            Commit every fix currently staged for review in one call — the batch
            equivalent of confirm_fix (only relevant when resolvr.require-confirmation
            is enabled). Use list_pending_fixes to review what's staged first.
            """)
    public String confirmAllPendingFixes() {
        List<PendingFix> fixes = new ArrayList<>(pendingFixes.listAll());
        List<FixResult> results = new ArrayList<>();

        for (PendingFix fix : fixes) {
            try {
                String sha = rest.commitFileChange(fix.owner(), fix.repo(), fix.branch(),
                        fix.filePath(), fix.newContent(), fix.commitMessage());
                if (fix.threadId() != null) {
                    graphQL.resolveThread(fix.threadId());
                }
                pendingFixes.remove(fix.token());
                results.add(FixResult.success(fix.threadId(), fix.filePath(), sha));
            } catch (Exception e) {
                Log.errorf(e, "confirmAllPendingFixes failed for token %s", fix.token());
                results.add(FixResult.failure(fix.threadId(), fix.filePath(), e.getMessage()));
            }
        }

        long succeeded = results.stream().filter(FixResult::resolved).count();
        try {
            return mapper.writeValueAsString(Map.of(
                    "summary", succeeded + "/" + results.size() + " staged fixes committed",
                    "results", results
            ));
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool 11 — Discard a staged fix without committing it
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            Discard a fix that was staged by apply_fix or auto_resolve_all without
            committing it (only relevant when resolvr.require-confirmation is enabled).
            """)
    public String discardPendingFix(
            @ToolArg(description = "Token returned by apply_fix or auto_resolve_all when staged") String token
    ) {
        PendingFix removed = pendingFixes.remove(token);
        if (removed == null) {
            return "{\"discarded\":false,\"error\":\"no pending fix with that token\"}";
        }
        return "{\"discarded\":true,\"token\":\"" + token + "\"}";
    }
}
