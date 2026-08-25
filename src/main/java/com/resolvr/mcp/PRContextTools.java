package com.resolvr.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resolvr.pr.PRContextService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * PR Context Engine (spec §8, Phase 2) — the high-level "give me everything
 * I need to understand this PR" call, built on top of get_workspace_pr_context.
 * Read-only.
 */
@ApplicationScoped
public class PRContextTools {

    @Inject
    PRContextService prContext;

    private final ObjectMapper mapper = new ObjectMapper();

    @Tool(name = "get_pr_context", description = """
            Retrieve full structured context for the PR associated with the current workspace:
            PR metadata (title, description, state, author, base/head branch, head sha), review
            threads (both resolved and unresolved, with resolution state), PR comments, changed
            files, the unified diff, commits, and CI/check status (overallStatus one of PASSING,
            FAILING, PENDING, UNKNOWN). Also includes workspace/sync info (local branch, local
            HEAD sha, PR HEAD sha, upToDate, working-tree clean/dirty) from workspace discovery.

            Call get_workspace_pr_context first if you haven't already this session — this tool
            reuses the same discovery. If discovery didn't resolve a single PR (no match, multiple
            matches, no GitHub remote, detached HEAD, etc.) this returns that same discovery result
            unchanged rather than guessing which PR to use.

            If any individual section (comments, files, diff, commits, CI) fails to load from
            GitHub, the rest of the context is still returned, with that section carrying an
            "error" field instead of being silently omitted or fabricated.

            Read-only: never switches branches, edits files, commits, pushes, or resolves threads.
            """)
    public String getPrContext(
            @ToolArg(description = "Absolute path to the workspace/repository root. Omit to use the "
                    + "server's configured or current working directory.", required = false)
            String workspacePath
    ) {
        try {
            Map<String, Object> context = prContext.getContext(workspacePath);
            return mapper.writeValueAsString(context);
        } catch (Exception e) {
            Log.errorf(e, "getPrContext failed for %s", workspacePath);
            return McpErrors.error(e.getMessage());
        }
    }
}
