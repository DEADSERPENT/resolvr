package com.resolvr.mcp;

import com.resolvr.ci.CiStatusService;
import com.resolvr.github.GitHubRestClient;
import com.resolvr.model.CheckRun;
import com.resolvr.pr.WorkspacePrContextService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the CiStatusTools MCP surface: JSON envelope shape and the
 * try/catch -> {"error": ...} fallback, same convention as ResolutionTools/
 * PRContextTools. The underlying aggregation logic is covered by
 * CiStatusServiceTest — this only checks the tool layer wraps it correctly.
 */
class CiStatusToolsTest {

    static class FakeDiscovery extends WorkspacePrContextService {
        Map<String, Object> toReturn;
        RuntimeException toThrow;

        @Override
        public Map<String, Object> getContext(String workspacePath) {
            if (toThrow != null) throw toThrow;
            return toReturn;
        }
    }

    static class FakeRestClient extends GitHubRestClient {
        List<CheckRun> checkRuns = List.of();

        @Override
        public List<CheckRun> listCheckRuns(String owner, String repo, String ref) {
            return checkRuns;
        }

        @Override
        public Optional<String> getCheckRunLogText(String owner, String repo, long checkRunId) {
            return Optional.empty();
        }
    }

    private FakeDiscovery discovery;
    private FakeRestClient rest;

    private CiStatusTools newTools() {
        CiStatusTools t = new CiStatusTools();
        discovery = new FakeDiscovery();
        rest = new FakeRestClient();
        CiStatusService service = new CiStatusService();
        service.workspacePrContext = discovery;
        service.rest = rest;
        service.logMaxLines = 300;
        service.logMaxBytes = 65536;
        t.ciStatus = service;
        return t;
    }

    private static Map<String, Object> discoveryWithOnePr() {
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("path", "/repo");
        workspace.put("headSha", "sha1");

        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("number", 42);
        pr.put("headSha", "sha1");
        pr.put("state", "OPEN");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("repository", Map.of("owner", "octocat", "name", "hello-world"));
        ctx.put("workspace", workspace);
        ctx.put("pullRequest", pr);
        ctx.put("sync", Map.of("upToDate", true));
        return ctx;
    }

    @Test
    void getCiStatus_returnsOverallStatusJson() {
        CiStatusTools tools = newTools();
        discovery.toReturn = discoveryWithOnePr();
        rest.checkRuns = List.of(new CheckRun(1L, "unit-tests", "completed", "success", "https://x/1"));

        String json = tools.getCiStatus("/repo");

        assertTrue(json.contains("\"overallStatus\":\"PASSING\""), json);
    }

    @Test
    void getCiStatus_discoveryThrows_returnsErrorJson() {
        CiStatusTools tools = newTools();
        discovery.toThrow = new RuntimeException("git not found");

        String json = tools.getCiStatus("/repo");

        assertTrue(json.contains("\"error\""), json);
    }

    @Test
    void getCiFailureLogs_noFailures_returnsEmptyFailuresJson() {
        CiStatusTools tools = newTools();
        discovery.toReturn = discoveryWithOnePr();
        rest.checkRuns = List.of(new CheckRun(1L, "unit-tests", "completed", "success", "https://x/1"));

        String json = tools.getCiFailureLogs("/repo", null);

        assertTrue(json.contains("\"failures\":[]"), json);
    }

    @Test
    void getCiFailureLogs_withCheckNamesFilter_parsesJsonArrayArg() {
        CiStatusTools tools = newTools();
        discovery.toReturn = discoveryWithOnePr();
        rest.checkRuns = List.of(new CheckRun(1L, "build", "completed", "failure", "https://x/1"));

        String json = tools.getCiFailureLogs("/repo", "[\"build\"]");

        assertTrue(json.contains("\"checkName\":\"build\""), json);
    }

    @Test
    void getCiFailureLogs_discoveryThrows_returnsErrorJson() {
        CiStatusTools tools = newTools();
        discovery.toThrow = new RuntimeException("git not found");

        String json = tools.getCiFailureLogs("/repo", null);

        assertTrue(json.contains("\"error\""), json);
    }
}
