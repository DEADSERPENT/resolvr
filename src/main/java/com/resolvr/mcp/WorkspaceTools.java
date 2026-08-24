package com.resolvr.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resolvr.pr.WorkspacePrContextService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Discovery tools for the default Manual/On-demand mode (spec §6.1): the
 * first thing an agent calls in a session, before any webhook or polling
 * mechanism is involved. Read-only — never switches branches, edits files,
 * commits, pushes, or resolves threads.
 */
@ApplicationScoped
public class WorkspaceTools {

    @Inject
    WorkspacePrContextService workspacePrContext;

    private final ObjectMapper mapper = new ObjectMapper();

    @Tool(name = "get_workspace_pr_context", description = """
            Discover what the local workspace is looking at: resolves the Git repository,
            origin remote, current branch, local HEAD sha, and working-tree cleanliness,
            then finds the open GitHub PR (if any) whose head branch matches. Call this
            FIRST in a session — no webhook event or prior poll is required.
            Read-only: never switches branches, edits files, commits, pushes, or resolves
            threads. Returns repository, workspace, pullRequest, workingTree, and sync
            (local HEAD vs. PR HEAD) fields. If no PR matches, if multiple PRs match, or
            if the remote isn't a GitHub repository, that is reported in `message` rather
            than guessed at.
            """)
    public String getWorkspacePrContext(
            @ToolArg(description = "Absolute path to the workspace/repository root. Omit to use the "
                    + "server's configured or current working directory.", required = false)
            String workspacePath
    ) {
        try {
            Map<String, Object> context = workspacePrContext.getContext(workspacePath);
            return mapper.writeValueAsString(context);
        } catch (Exception e) {
            Log.errorf(e, "getWorkspacePrContext failed for %s", workspacePath);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @Tool(name = "get_current_pr", description = """
            Thin wrapper around get_workspace_pr_context: returns just the repository and
            pullRequest for the current workspace, without the fuller workspace/sync detail.
            Call get_workspace_pr_context directly if you also need local HEAD sha, working
            tree state, or the local-vs-remote sync check.
            """)
    public String getCurrentPr(
            @ToolArg(description = "Absolute path to the workspace/repository root. Omit to use the "
                    + "server's configured or current working directory.", required = false)
            String workspacePath
    ) {
        try {
            Map<String, Object> context = workspacePrContext.getContext(workspacePath);
            Map<String, Object> summary = new LinkedHashMap<>();
            copyIfPresent(context, summary, "repository");
            copyIfPresent(context, summary, "pullRequest");
            copyIfPresent(context, summary, "multipleMatches");
            copyIfPresent(context, summary, "candidates");
            copyIfPresent(context, summary, "message");
            copyIfPresent(context, summary, "error");
            return mapper.writeValueAsString(summary);
        } catch (Exception e) {
            Log.errorf(e, "getCurrentPr failed for %s", workspacePath);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
    }
}
