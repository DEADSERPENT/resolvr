package com.resolvr.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resolvr.resolution.ResolutionService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Phase 3 — local-first commit/push with one approval boundary. Copilot owns
 * editing the workspace and running tests via its own VS Code tools; these
 * tools only take over once Copilot says it's done, and they re-verify Git/
 * GitHub state independently rather than trusting that claim.
 */
@ApplicationScoped
public class ResolutionTools {

    @Inject
    ResolutionService resolution;

    private final ObjectMapper mapper = new ObjectMapper();

    @Tool(name = "get_local_changes", description = """
            Read-only: what's currently changed in the local working tree (staged or not, including
            untracked files), derived from `git status` + `git diff HEAD --numstat` — not from asking
            the agent what it changed. Returns branch, headSha, workingTreeClean, per-file status
            (MODIFIED/ADDED/DELETED/RENAMED/NEW) with additions/deletions, and diffStats totals.
            Never sends full file contents.
            """)
    public String getLocalChanges(
            @ToolArg(description = "Absolute path to the workspace/repository root. Omit to use the "
                    + "server's configured or current working directory.", required = false)
            String workspacePath
    ) {
        try {
            return mapper.writeValueAsString(resolution.getLocalChanges(workspacePath));
        } catch (Exception e) {
            Log.errorf(e, "getLocalChanges failed for %s", workspacePath);
            return McpErrors.error(e.getMessage());
        }
    }

    @Tool(name = "prepare_resolution_summary", description = """
            Read-only: builds the approval package for the current local changes against the current
            PR, and stages it (returning a token) for commit_and_push_resolution. Does NOT commit,
            push, or resolve anything.

            Independently verifies before staging: current branch equals the PR's head branch (and
            isn't the base branch), local HEAD equals the PR's remote HEAD (refuses if the branch
            isn't synchronized), and that the working tree actually has changes. Refuses with a clear
            `error` if any of those don't hold, or if discovery didn't resolve exactly one PR.

            addressedThreadIds is optional — the thread IDs the agent believes this change addresses.
            These are recorded as candidates only ("Copilot-reported; not independently verified"),
            not resolved yet. Present this summary to the developer; only call
            commit_and_push_resolution after they explicitly approve.
            """)
    public String prepareResolutionSummary(
            @ToolArg(description = "Absolute path to the workspace/repository root. Omit to use the "
                    + "server's configured or current working directory.", required = false)
            String workspacePath,
            @ToolArg(description = "Commit message describing the fix, e.g. "
                    + "'fix: address review feedback on AuthService null check'")
            String commitMessage,
            @ToolArg(description = "JSON array of review thread IDs this change is believed to address, "
                    + "e.g. [\"RT_1\",\"RT_2\"]. Optional — pass an empty array or omit if none.",
                    required = false)
            String addressedThreadIdsJson
    ) {
        try {
            List<String> threadIds = parseThreadIds(addressedThreadIdsJson);
            return mapper.writeValueAsString(resolution.prepareResolutionSummary(workspacePath, commitMessage, threadIds));
        } catch (Exception e) {
            Log.errorf(e, "prepareResolutionSummary failed for %s", workspacePath);
            return McpErrors.error(e.getMessage());
        }
    }

    @Tool(name = "commit_and_push_resolution", description = """
            THE APPROVAL BOUNDARY. Only call this after the developer has explicitly approved the
            summary from prepare_resolution_summary — never on your own initiative.

            Before committing anything, independently re-checks: current branch still matches, local
            HEAD hasn't changed, the approved files are still changed in the working tree, the PR is
            still open on GitHub, and the PR's remote HEAD hasn't moved. If any of those changed since
            the summary was prepared, this refuses and asks you to re-run prepare_resolution_summary
            rather than pushing against stale assumptions.

            Stages and commits exactly the files captured in the summary (never `git add .`, so
            unrelated developer changes in the working tree are never swept in), then pushes to the
            PR's branch. Does NOT resolve any review threads — call resolve_addressed_threads next.
            """)
    public String commitAndPushResolution(
            @ToolArg(description = "Token returned by prepare_resolution_summary") String token
    ) {
        try {
            return mapper.writeValueAsString(resolution.commitAndPushResolution(token));
        } catch (Exception e) {
            Log.errorf(e, "commitAndPushResolution failed for token %s", token);
            return McpErrors.error(e.getMessage());
        }
    }

    @Tool(name = "resolve_addressed_threads", description = """
            Marks review threads resolved on GitHub. Only usable after commit_and_push_resolution has
            succeeded for this token (status PUSHED) — refuses otherwise, so a failed or not-yet-
            approved push can never leave a thread marked resolved. If threadIdsJson is omitted, uses
            the addressedThreadIds recorded when the resolution was prepared.
            """)
    public String resolveAddressedThreads(
            @ToolArg(description = "Token returned by prepare_resolution_summary / commit_and_push_resolution")
            String token,
            @ToolArg(description = "JSON array of thread IDs to resolve. Omit to use the threads recorded "
                    + "at prepare_resolution_summary time.", required = false)
            String threadIdsJson
    ) {
        try {
            List<String> threadIds = parseThreadIds(threadIdsJson);
            return mapper.writeValueAsString(resolution.resolveAddressedThreads(token, threadIds));
        } catch (Exception e) {
            Log.errorf(e, "resolveAddressedThreads failed for token %s", token);
            return McpErrors.error(e.getMessage());
        }
    }

    @Tool(name = "discard_resolution", description = """
            Discards a prepared resolution without committing or pushing anything — use when the
            developer rejects the summary from prepare_resolution_summary.
            """)
    public String discardResolution(
            @ToolArg(description = "Token returned by prepare_resolution_summary") String token
    ) {
        try {
            return mapper.writeValueAsString(resolution.discardResolution(token));
        } catch (Exception e) {
            Log.errorf(e, "discardResolution failed for token %s", token);
            return McpErrors.error(e.getMessage());
        }
    }

    private List<String> parseThreadIds(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, new TypeReference<List<String>>() {});
    }
}
