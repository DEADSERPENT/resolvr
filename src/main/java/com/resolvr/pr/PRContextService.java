package com.resolvr.pr;

import com.resolvr.github.GitHubGraphQLClient;
import com.resolvr.github.GitHubRestClient;
import com.resolvr.model.ReviewThread;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PR Context Engine (spec §8, Phase 2): aggregates everything Copilot needs
 * to understand a PR — metadata, review threads, comments, changed files,
 * diff, commits, and CI/check status — into one structured response, built
 * on top of the Phase 1 workspace → PR discovery.
 *
 * Read-only. Never switches branches, edits files, commits, pushes, or
 * resolves threads. If discovery is ambiguous or absent, that result is
 * returned as-is rather than guessed at. If one section of GitHub data
 * fails to load, the rest of the context is still returned, with that
 * section's `error` explaining what went wrong — nothing is fabricated.
 */
@ApplicationScoped
public class PRContextService {

    private static final Set<String> FAILING_CONCLUSIONS =
            Set.of("failure", "timed_out", "cancelled", "action_required", "stale");
    private static final Set<String> PASSING_CONCLUSIONS =
            Set.of("success", "neutral", "skipped");

    @Inject
    WorkspacePrContextService workspacePrContext;

    @Inject
    GitHubRestClient rest;

    @Inject
    GitHubGraphQLClient graphQL;

    @SuppressWarnings("unchecked")
    public Map<String, Object> getContext(String workspacePath) {
        Map<String, Object> discovery = workspacePrContext.getContext(workspacePath);

        // Discovery didn't resolve a single PR unambiguously — surface exactly what it found
        // (not-a-repo, no origin, non-GitHub remote, detached HEAD, no match, multiple matches,
        // or a GitHub failure during discovery itself) rather than guessing.
        Object repositoryObj = discovery.get("repository");
        Object pullRequestObj = discovery.get("pullRequest");
        if (repositoryObj == null || Boolean.TRUE.equals(discovery.get("multipleMatches")) || pullRequestObj == null) {
            return discovery;
        }

        Map<String, Object> repository = (Map<String, Object>) repositoryObj;
        Map<String, Object> prSummary = (Map<String, Object>) pullRequestObj;
        String owner = (String) repository.get("owner");
        String name = (String) repository.get("name");
        int number = (Integer) prSummary.get("number");
        String headSha = (String) prSummary.get("headSha");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repository", repository);
        result.put("workspace", discovery.get("workspace"));
        result.put("workingTree", discovery.get("workingTree"));
        result.put("sync", discovery.get("sync"));

        try {
            result.put("pullRequest", rest.getPullRequest(owner, name, number));
        } catch (Exception e) {
            Log.errorf(e, "Failed to fetch PR metadata for %s/%s#%d", owner, name, number);
            Map<String, Object> fallback = new LinkedHashMap<>(prSummary);
            fallback.put("error", e.getMessage());
            result.put("pullRequest", fallback);
        }

        try {
            List<ReviewThread> threads = graphQL.getAllReviewThreads(owner, name, number);
            long unresolved = threads.stream().filter(t -> !t.resolved()).count();
            Map<String, Object> reviewInfo = new LinkedHashMap<>();
            reviewInfo.put("threads", threads);
            reviewInfo.put("totalCount", threads.size());
            reviewInfo.put("unresolvedCount", unresolved);
            result.put("reviewThreads", reviewInfo);
        } catch (Exception e) {
            Log.errorf(e, "Failed to fetch review threads for %s/%s#%d", owner, name, number);
            result.put("reviewThreads", Map.of("error", e.getMessage()));
        }

        try {
            result.put("comments", rest.listIssueComments(owner, name, number));
        } catch (Exception e) {
            Log.errorf(e, "Failed to fetch PR comments for %s/%s#%d", owner, name, number);
            result.put("comments", Map.of("error", e.getMessage()));
        }

        try {
            result.put("changedFiles", rest.listChangedFiles(owner, name, number));
        } catch (Exception e) {
            Log.errorf(e, "Failed to fetch changed files for %s/%s#%d", owner, name, number);
            result.put("changedFiles", Map.of("error", e.getMessage()));
        }

        try {
            result.put("diff", rest.getDiff(owner, name, number));
        } catch (Exception e) {
            Log.errorf(e, "Failed to fetch diff for %s/%s#%d", owner, name, number);
            result.put("diff", Map.of("error", e.getMessage()));
        }

        try {
            result.put("commits", rest.listCommits(owner, name, number));
        } catch (Exception e) {
            Log.errorf(e, "Failed to fetch commits for %s/%s#%d", owner, name, number);
            result.put("commits", Map.of("error", e.getMessage()));
        }

        try {
            result.put("ci", buildCiSummary(rest.listCheckRuns(owner, name, headSha)));
        } catch (Exception e) {
            Log.errorf(e, "Failed to fetch CI status for %s/%s@%s", owner, name, headSha);
            Map<String, Object> ci = new LinkedHashMap<>();
            ci.put("overallStatus", "UNKNOWN");
            ci.put("error", e.getMessage());
            result.put("ci", ci);
        }

        return result;
    }

    private Map<String, Object> buildCiSummary(List<com.resolvr.model.CheckRun> checks) {
        String overall;
        if (checks.isEmpty()) {
            overall = "UNKNOWN";
        } else if (checks.stream().anyMatch(c -> c.conclusion() != null && FAILING_CONCLUSIONS.contains(c.conclusion()))) {
            overall = "FAILING";
        } else if (checks.stream().anyMatch(c -> !"completed".equals(c.status()))) {
            overall = "PENDING";
        } else if (checks.stream().allMatch(c -> c.conclusion() != null && PASSING_CONCLUSIONS.contains(c.conclusion()))) {
            overall = "PASSING";
        } else {
            overall = "UNKNOWN";
        }
        Map<String, Object> ci = new LinkedHashMap<>();
        ci.put("overallStatus", overall);
        ci.put("checks", checks);
        return ci;
    }
}
