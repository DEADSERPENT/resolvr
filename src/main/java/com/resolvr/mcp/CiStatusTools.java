package com.resolvr.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resolvr.ci.CiStatusService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Phase 5 — the CI feedback loop. Read-only, and deliberately non-blocking: neither tool
 * here waits for CI to finish. Call get_ci_status repeatedly with your own delay between
 * calls (e.g. every 15-30s) after commit_and_push_resolution reports PUSHED, and only call
 * get_ci_failure_logs once a check comes back FAILING.
 */
@ApplicationScoped
public class CiStatusTools {

    @Inject
    CiStatusService ciStatus;

    private final ObjectMapper mapper = new ObjectMapper();

    @Tool(name = "get_ci_status", description = """
            Read-only, cheap, poll-friendly: current CI/check status for the PR associated with the
            current workspace, at its current remote HEAD sha. Returns overallStatus (one of PASSING,
            FAILING, PENDING, UNKNOWN) and the individual checks (name, status, conclusion, htmlUrl).

            Lighter than get_pr_context — this skips review threads, comments, diff, and commits, so
            it's the right tool to call repeatedly while waiting on CI after a push, rather than
            re-fetching the full PR context each time. This tool does not block or wait: call it,
            look at overallStatus, and if it's still PENDING, wait ~15-30s yourself before calling
            again rather than expecting this call to hang until CI finishes. Stop after a bounded
            number of attempts and report the situation rather than polling forever.

            If discovery didn't resolve a single PR (no match, multiple matches, no GitHub remote,
            detached HEAD, etc.) this returns that same discovery result unchanged rather than
            guessing which PR to check.
            """)
    public String getCiStatus(
            @ToolArg(description = "Absolute path to the workspace/repository root. Omit to use the "
                    + "server's configured or current working directory.", required = false)
            String workspacePath
    ) {
        try {
            return mapper.writeValueAsString(ciStatus.getStatus(workspacePath));
        } catch (Exception e) {
            Log.errorf(e, "getCiStatus failed for %s", workspacePath);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @Tool(name = "get_ci_failure_logs", description = """
            Read-only: fetches truncated log excerpts for the PR's currently FAILING checks, so you
            can diagnose a CI failure without leaving the editor. Only useful once get_ci_status has
            reported a check as FAILING — calling it beforehand just returns an empty failures list.

            Each failure includes checkName, conclusion, htmlUrl, and — when available — a tail-
            truncated logExcerpt (the last portion of the log; failures/stack traces are almost always
            near the end). Some checks have no fetchable log here (checks created by a third-party CI
            app rather than the native GitHub Actions app) — those come back with logAvailable=false
            and you should fall back to htmlUrl. When a log is truncated, truncated=true and
            originalLineCount tell you how much was cut; the full log is always at htmlUrl.

            After reading the excerpt, fix the problem locally with your own tools (same as any other
            review-comment fix), then go through prepare_resolution_summary → developer approval →
            commit_and_push_resolution again for the new fix — never re-push without a fresh approval.
            """)
    public String getCiFailureLogs(
            @ToolArg(description = "Absolute path to the workspace/repository root. Omit to use the "
                    + "server's configured or current working directory.", required = false)
            String workspacePath,
            @ToolArg(description = "JSON array of check names to limit the result to, e.g. [\"build\"]. "
                    + "Optional — omit or pass an empty array to include every failing check.",
                    required = false)
            String checkNamesJson
    ) {
        try {
            List<String> checkNames = parseCheckNames(checkNamesJson);
            return mapper.writeValueAsString(ciStatus.getFailureLogs(workspacePath, checkNames));
        } catch (Exception e) {
            Log.errorf(e, "getCiFailureLogs failed for %s", workspacePath);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private List<String> parseCheckNames(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, new TypeReference<List<String>>() {});
    }
}
