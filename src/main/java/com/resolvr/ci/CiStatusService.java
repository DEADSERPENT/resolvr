package com.resolvr.ci;

import com.resolvr.github.GitHubRestClient;
import com.resolvr.model.CheckRun;
import com.resolvr.model.CiConclusions;
import com.resolvr.pr.WorkspacePrContextService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 5 — the CI feedback loop's read side. Cheap enough to poll repeatedly after
 * commit_and_push_resolution (unlike get_pr_context, which also fetches threads, comments,
 * diff, and commits every call), and — once a check is failing — fetches a truncated log
 * excerpt so the agent can diagnose the failure without leaving the editor.
 *
 * Read-only. Never switches branches, edits files, commits, pushes, or resolves threads.
 * Reuses the same workspace → PR discovery as every other Phase 1+ tool, and the same
 * "don't guess" convention: if discovery didn't resolve exactly one PR, that result is
 * returned unchanged rather than fabricating a CI status for no PR.
 */
@ApplicationScoped
public class CiStatusService {

    @Inject
    public WorkspacePrContextService workspacePrContext;

    @Inject
    public GitHubRestClient rest;

    @ConfigProperty(name = "resolvr.ci.log-max-lines", defaultValue = "300")
    public int logMaxLines;

    @ConfigProperty(name = "resolvr.ci.log-max-bytes", defaultValue = "65536")
    public int logMaxBytes;

    // ─── get_ci_status — cheap, poll-friendly ───────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> getStatus(String workspacePath) {
        Map<String, Object> discovery = workspacePrContext.getContext(workspacePath);

        Object repositoryObj = discovery.get("repository");
        Object pullRequestObj = discovery.get("pullRequest");
        if (repositoryObj == null || Boolean.TRUE.equals(discovery.get("multipleMatches")) || pullRequestObj == null) {
            return discovery;
        }

        Map<String, Object> repository = (Map<String, Object>) repositoryObj;
        Map<String, Object> prSummary = (Map<String, Object>) pullRequestObj;
        String owner = (String) repository.get("owner");
        String name = (String) repository.get("name");
        String headSha = (String) prSummary.get("headSha");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repository", repository);
        result.put("pullRequest", prSummary);
        result.put("headSha", headSha);
        result.put("sync", discovery.get("sync"));
        result.put("ci", fetchCiSummary(owner, name, headSha));
        return result;
    }

    // ─── get_ci_failure_logs — only meaningful once get_ci_status reports FAILING ─

    @SuppressWarnings("unchecked")
    public Map<String, Object> getFailureLogs(String workspacePath, List<String> checkNames) {
        Map<String, Object> discovery = workspacePrContext.getContext(workspacePath);

        Object repositoryObj = discovery.get("repository");
        Object pullRequestObj = discovery.get("pullRequest");
        if (repositoryObj == null || Boolean.TRUE.equals(discovery.get("multipleMatches")) || pullRequestObj == null) {
            return discovery;
        }

        Map<String, Object> repository = (Map<String, Object>) repositoryObj;
        Map<String, Object> prSummary = (Map<String, Object>) pullRequestObj;
        String owner = (String) repository.get("owner");
        String name = (String) repository.get("name");
        String headSha = (String) prSummary.get("headSha");

        List<CheckRun> checks;
        try {
            checks = rest.listCheckRuns(owner, name, headSha);
        } catch (Exception e) {
            Log.errorf(e, "Failed to fetch check runs for %s/%s@%s", owner, name, headSha);
            return error("Could not fetch CI status: " + e.getMessage());
        }

        boolean filterByName = checkNames != null && !checkNames.isEmpty();
        List<CheckRun> failing = checks.stream()
                .filter(c -> c.conclusion() != null && CiConclusions.FAILING.contains(c.conclusion()))
                .filter(c -> !filterByName || checkNames.contains(c.name()))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repository", repository);
        result.put("pullRequest", prSummary);
        result.put("headSha", headSha);
        result.put("overallStatus", CiConclusions.overallStatus(checks));

        if (failing.isEmpty()) {
            result.put("failures", List.of());
            result.put("message", "No failing checks" + (filterByName ? " matching the given names." : " for this commit."));
            return result;
        }

        List<Map<String, Object>> failures = new ArrayList<>();
        for (CheckRun check : failing) {
            failures.add(buildFailureDetail(owner, name, check));
        }
        result.put("failures", failures);
        return result;
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private Map<String, Object> fetchCiSummary(String owner, String name, String headSha) {
        try {
            List<CheckRun> checks = rest.listCheckRuns(owner, name, headSha);
            Map<String, Object> ci = new LinkedHashMap<>();
            ci.put("overallStatus", CiConclusions.overallStatus(checks));
            ci.put("checks", checks);
            return ci;
        } catch (Exception e) {
            Log.errorf(e, "Failed to fetch CI status for %s/%s@%s", owner, name, headSha);
            Map<String, Object> ci = new LinkedHashMap<>();
            ci.put("overallStatus", "UNKNOWN");
            ci.put("error", e.getMessage());
            return ci;
        }
    }

    private Map<String, Object> buildFailureDetail(String owner, String name, CheckRun check) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("checkName", check.name());
        detail.put("conclusion", check.conclusion());
        detail.put("htmlUrl", check.htmlUrl());

        try {
            Optional<String> logText = rest.getCheckRunLogText(owner, name, check.id());
            if (logText.isEmpty()) {
                detail.put("logAvailable", false);
                detail.put("message", "No log available for this check (not created by the GitHub Actions "
                        + "app) — see htmlUrl for details.");
                return detail;
            }
            detail.put("logAvailable", true);
            TailExcerpt excerpt = tailExcerpt(logText.get());
            detail.put("logExcerpt", excerpt.text());
            detail.put("truncated", excerpt.truncated());
            if (excerpt.truncated()) {
                detail.put("originalLineCount", excerpt.originalLineCount());
                detail.put("message", "Log truncated to the last " + logMaxLines + " lines ("
                        + excerpt.originalLineCount() + " total) — see htmlUrl for the full log.");
            }
        } catch (Exception e) {
            Log.errorf(e, "Failed to fetch log for check %s (id %d) on %s/%s", check.name(), check.id(), owner, name);
            detail.put("logAvailable", false);
            detail.put("error", e.getMessage());
        }
        return detail;
    }

    private record TailExcerpt(String text, boolean truncated, int originalLineCount) {
    }

    /**
     * Keeps the last logMaxLines lines (failures/stack traces are almost always near the end of a
     * CI log), then hard-caps by byte size in case a single line is enormous (e.g. minified output).
     */
    private TailExcerpt tailExcerpt(String fullLog) {
        String[] lines = fullLog.split("\n", -1);
        boolean lineTruncated = lines.length > logMaxLines;
        int start = lineTruncated ? lines.length - logMaxLines : 0;
        String joined = String.join("\n", Arrays.copyOfRange(lines, start, lines.length));

        byte[] bytes = joined.getBytes(StandardCharsets.UTF_8);
        boolean byteTruncated = bytes.length > logMaxBytes;
        if (byteTruncated) {
            joined = new String(bytes, bytes.length - logMaxBytes, logMaxBytes, StandardCharsets.UTF_8);
        }

        return new TailExcerpt(joined, lineTruncated || byteTruncated, lines.length);
    }

    private Map<String, Object> error(String message) {
        return Map.of("error", message);
    }
}
