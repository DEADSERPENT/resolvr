package com.resolvr.ci;

import com.resolvr.github.GitHubApiException;
import com.resolvr.github.GitHubRestClient;
import com.resolvr.model.CheckRun;
import com.resolvr.pr.WorkspacePrContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CiStatusService's aggregation logic in isolation from the network and Git
 * boundaries: WorkspacePrContextService and GitHubRestClient are faked with canned/
 * controllable subclasses, same pattern as PRContextServiceTest.
 */
class CiStatusServiceTest {

    static class FakeDiscovery extends WorkspacePrContextService {
        Map<String, Object> toReturn;

        @Override
        public Map<String, Object> getContext(String workspacePath) {
            return toReturn;
        }
    }

    static class FakeRestClient extends GitHubRestClient {
        List<CheckRun> checkRuns = List.of();
        RuntimeException checkRunsError;

        Map<Long, Optional<String>> logsById = new LinkedHashMap<>();
        Map<Long, RuntimeException> logErrorsById = new LinkedHashMap<>();

        @Override
        public List<CheckRun> listCheckRuns(String owner, String repo, String ref) {
            if (checkRunsError != null) throw checkRunsError;
            return checkRuns;
        }

        @Override
        public Optional<String> getCheckRunLogText(String owner, String repo, long checkRunId) {
            if (logErrorsById.containsKey(checkRunId)) throw logErrorsById.get(checkRunId);
            return logsById.getOrDefault(checkRunId, Optional.empty());
        }
    }

    private FakeDiscovery discovery;
    private FakeRestClient rest;
    private CiStatusService service;

    @BeforeEach
    void setUp() {
        discovery = new FakeDiscovery();
        rest = new FakeRestClient();
        service = new CiStatusService();
        service.workspacePrContext = discovery;
        service.rest = rest;
        service.logMaxLines = 300;
        service.logMaxBytes = 65536;
    }

    private static Map<String, Object> discoveryWithOnePr(String headSha) {
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("path", "/repo");
        workspace.put("branch", "feature/auth");
        workspace.put("headSha", headSha);

        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("number", 42);
        pr.put("headBranch", "feature/auth");
        pr.put("headSha", headSha);
        pr.put("state", "OPEN");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("repository", Map.of("owner", "octocat", "name", "hello-world"));
        ctx.put("workspace", workspace);
        ctx.put("pullRequest", pr);
        ctx.put("sync", Map.of("upToDate", true));
        return ctx;
    }

    // ─── best case ────────────────────────────────────────────────────────────

    @Test
    void getStatus_allPassing_overallStatusPassing() {
        discovery.toReturn = discoveryWithOnePr("sha1");
        rest.checkRuns = List.of(new CheckRun(1L, "unit-tests", "completed", "success", "https://x/1"));

        Map<String, Object> result = service.getStatus("/repo");

        @SuppressWarnings("unchecked")
        Map<String, Object> ci = (Map<String, Object>) result.get("ci");
        assertEquals("PASSING", ci.get("overallStatus"));
        assertEquals("sha1", result.get("headSha"));
    }

    @Test
    void getFailureLogs_noFailures_emptyListWithMessage() {
        discovery.toReturn = discoveryWithOnePr("sha1");
        rest.checkRuns = List.of(new CheckRun(1L, "unit-tests", "completed", "success", "https://x/1"));

        Map<String, Object> result = service.getFailureLogs("/repo", List.of());

        assertEquals("PASSING", result.get("overallStatus"));
        assertEquals(List.of(), result.get("failures"));
        assertNotNull(result.get("message"));
    }

    // ─── good case: status changes across polls ─────────────────────────────

    @Test
    void getStatus_pendingThenFailing_reflectsLatestPollEachTime() {
        discovery.toReturn = discoveryWithOnePr("sha1");
        rest.checkRuns = List.of(new CheckRun(1L, "build", "in_progress", null, null));

        Map<String, Object> first = service.getStatus("/repo");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstCi = (Map<String, Object>) first.get("ci");
        assertEquals("PENDING", firstCi.get("overallStatus"));

        rest.checkRuns = List.of(new CheckRun(1L, "build", "completed", "failure", "https://x/1"));

        Map<String, Object> second = service.getStatus("/repo");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondCi = (Map<String, Object>) second.get("ci");
        assertEquals("FAILING", secondCi.get("overallStatus"));
    }

    // ─── worst case ───────────────────────────────────────────────────────────

    @Test
    void getFailureLogs_logAvailable_returnsExcerpt() {
        discovery.toReturn = discoveryWithOnePr("sha1");
        rest.checkRuns = List.of(new CheckRun(1L, "build", "completed", "failure", "https://x/1"));
        rest.logsById.put(1L, Optional.of("line1\nline2\nERROR: build failed\n"));

        Map<String, Object> result = service.getFailureLogs("/repo", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) result.get("failures");
        assertEquals(1, failures.size());
        Map<String, Object> failure = failures.get(0);
        assertEquals("build", failure.get("checkName"));
        assertEquals(true, failure.get("logAvailable"));
        assertTrue(((String) failure.get("logExcerpt")).contains("ERROR: build failed"));
        assertEquals(false, failure.get("truncated"));
    }

    @Test
    void getFailureLogs_logLongerThanMaxLines_truncatedFromTheEnd() {
        discovery.toReturn = discoveryWithOnePr("sha1");
        rest.checkRuns = List.of(new CheckRun(1L, "build", "completed", "failure", "https://x/1"));
        rest.logsById.put(1L, Optional.of("line1\nline2\nline3\nline4\nERROR: build failed\n"));
        service.logMaxLines = 2;

        Map<String, Object> result = service.getFailureLogs("/repo", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) result.get("failures");
        Map<String, Object> failure = failures.get(0);
        assertEquals(true, failure.get("truncated"));
        String excerpt = (String) failure.get("logExcerpt");
        assertFalse(excerpt.contains("line1"), "tail truncation must drop the earliest lines, not the latest");
        assertTrue(excerpt.contains("ERROR: build failed"));
        assertNotNull(failure.get("originalLineCount"));
    }

    @Test
    void getFailureLogs_nonActionsCheck_logUnavailableNotThrown() {
        discovery.toReturn = discoveryWithOnePr("sha1");
        rest.checkRuns = List.of(new CheckRun(1L, "circleci/build", "completed", "failure", "https://x/1"));
        rest.logsById.put(1L, Optional.empty());

        Map<String, Object> result = service.getFailureLogs("/repo", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) result.get("failures");
        Map<String, Object> failure = failures.get(0);
        assertEquals(false, failure.get("logAvailable"));
        assertEquals("https://x/1", failure.get("htmlUrl"));
    }

    @Test
    void getFailureLogs_filtersByCheckName() {
        discovery.toReturn = discoveryWithOnePr("sha1");
        rest.checkRuns = List.of(
                new CheckRun(1L, "build", "completed", "failure", "https://x/1"),
                new CheckRun(2L, "lint", "completed", "failure", "https://x/2"));
        rest.logsById.put(1L, Optional.of("build broke"));
        rest.logsById.put(2L, Optional.of("lint broke"));

        Map<String, Object> result = service.getFailureLogs("/repo", List.of("build"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) result.get("failures");
        assertEquals(1, failures.size());
        assertEquals("build", failures.get(0).get("checkName"));
    }

    @Test
    void getStatus_gitHubApiDown_errorSurfacedNotFabricated() {
        discovery.toReturn = discoveryWithOnePr("sha1");
        rest.checkRunsError = new GitHubApiException(500, "boom");

        Map<String, Object> result = service.getStatus("/repo");

        @SuppressWarnings("unchecked")
        Map<String, Object> ci = (Map<String, Object>) result.get("ci");
        assertEquals("UNKNOWN", ci.get("overallStatus"));
        assertNotNull(ci.get("error"));
    }

    @Test
    void getFailureLogs_gitHubApiDown_returnsErrorMap() {
        discovery.toReturn = discoveryWithOnePr("sha1");
        rest.checkRunsError = new GitHubApiException(500, "boom");

        Map<String, Object> result = service.getFailureLogs("/repo", List.of());

        assertNotNull(result.get("error"));
    }

    @Test
    void getStatus_discoveryAmbiguous_returnedUnchanged() {
        Map<String, Object> ambiguous = new LinkedHashMap<>();
        ambiguous.put("repository", Map.of("owner", "octocat", "name", "hello-world"));
        ambiguous.put("multipleMatches", true);
        ambiguous.put("candidates", List.of());
        discovery.toReturn = ambiguous;

        Map<String, Object> result = service.getStatus("/repo");

        assertSame(ambiguous, result);
    }

    @Test
    void getStatus_noPrFound_returnedUnchanged() {
        Map<String, Object> noPr = new LinkedHashMap<>();
        noPr.put("repository", Map.of("owner", "octocat", "name", "hello-world"));
        noPr.put("pullRequest", null);
        noPr.put("message", "No open PR found for branch 'feature/auth'.");
        discovery.toReturn = noPr;

        Map<String, Object> result = service.getStatus("/repo");

        assertSame(noPr, result);
    }
}
