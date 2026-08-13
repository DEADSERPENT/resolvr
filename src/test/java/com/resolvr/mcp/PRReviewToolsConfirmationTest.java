package com.resolvr.mcp;

import com.resolvr.github.GitHubGraphQLClient;
import com.resolvr.github.GitHubRestClient;
import com.resolvr.orchestrator.PRReviewOrchestrator;
import com.resolvr.orchestrator.PendingFixStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit5 tests for the confirm-before-commit staging flow in
 * PRReviewTools. Uses lightweight subclasses of the GitHub clients that
 * record calls instead of hitting the network — the HTTP-layer behavior is
 * already covered by GitHubRestClientTest / GitHubGraphQLClientTest. What's
 * new here is whether PRReviewTools stages vs. commits, and whether
 * confirm/discard interact with the pending store correctly.
 */
class PRReviewToolsConfirmationTest {

    static class FakeRestClient extends GitHubRestClient {
        boolean commitCalled = false;
        String lastFilePath;

        @Override
        public String commitFileChange(String owner, String repo, String branch,
                                        String filePath, String newContent, String commitMessage) {
            commitCalled = true;
            lastFilePath = filePath;
            return "fake-sha-123";
        }
    }

    static class FakeGraphQLClient extends GitHubGraphQLClient {
        boolean resolveCalled = false;
        String lastResolvedThreadId;

        @Override
        public void resolveThread(String threadId) {
            resolveCalled = true;
            lastResolvedThreadId = threadId;
        }
    }

    private FakeRestClient rest;
    private FakeGraphQLClient graphQL;

    private PRReviewTools newTools(boolean requireConfirmation) {
        PRReviewTools t = new PRReviewTools();
        rest = new FakeRestClient();
        graphQL = new FakeGraphQLClient();
        t.rest = rest;
        t.graphQL = graphQL;
        t.orchestrator = new PRReviewOrchestrator();
        t.pendingFixes = new PendingFixStore();
        t.requireConfirmation = requireConfirmation;
        return t;
    }

    private String extractToken(String json) {
        int idx = json.indexOf("\"token\":\"");
        int start = idx + "\"token\":\"".length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    @Test
    void applyFix_withoutConfirmation_commitsImmediately() {
        PRReviewTools tools = newTools(false);

        String result = tools.applyFix("octocat", "hello-world", "main", "Foo.java", "new content", "fix: x");

        assertTrue(result.contains("\"success\":true"));
        assertTrue(rest.commitCalled);
        assertEquals(0, tools.pendingFixes.listAll().size());
    }

    @Test
    void applyFix_withConfirmationRequired_stagesInsteadOfCommitting() {
        PRReviewTools tools = newTools(true);

        String result = tools.applyFix("octocat", "hello-world", "main", "Foo.java", "new content", "fix: x");

        assertTrue(result.contains("\"staged\":true"));
        assertFalse(rest.commitCalled, "must not commit while staged");
        assertEquals(1, tools.pendingFixes.listAll().size());
    }

    @Test
    void listPendingFixes_showsPreviewWithoutFullContent() {
        PRReviewTools tools = newTools(true);
        tools.applyFix("octocat", "hello-world", "main", "Foo.java", "a very long file body here", "fix: x");

        String result = tools.listPendingFixes();

        assertTrue(result.contains("\"count\":1"));
        assertTrue(result.contains("\"filePath\":\"Foo.java\""));
        assertFalse(result.contains("a very long file body here"), "must not dump full content in the listing");
    }

    @Test
    void discardPendingFix_removesWithoutCommitting() {
        PRReviewTools tools = newTools(true);
        String staged = tools.applyFix("octocat", "hello-world", "main", "Foo.java", "content", "fix: x");
        String token = extractToken(staged);

        String result = tools.discardPendingFix(token);

        assertTrue(result.contains("\"discarded\":true"));
        assertFalse(rest.commitCalled);
        assertEquals(0, tools.pendingFixes.listAll().size());
    }

    @Test
    void discardPendingFix_unknownToken_returnsFalse() {
        PRReviewTools tools = newTools(true);
        assertTrue(tools.discardPendingFix("nope").contains("\"discarded\":false"));
    }

    @Test
    void confirmFix_commitsStagedFixAndRemovesIt() {
        PRReviewTools tools = newTools(true);
        String staged = tools.applyFix("octocat", "hello-world", "main", "Foo.java", "new content", "fix: x");
        String token = extractToken(staged);

        String result = tools.confirmFix(token);

        assertTrue(result.contains("\"success\":true"));
        assertTrue(result.contains("fake-sha-123"));
        assertTrue(rest.commitCalled);
        assertFalse(graphQL.resolveCalled, "apply_fix-staged fixes have no threadId to resolve");
        assertEquals(0, tools.pendingFixes.listAll().size());
    }

    @Test
    void confirmFix_unknownToken_returnsError() {
        PRReviewTools tools = newTools(true);
        String result = tools.confirmFix("does-not-exist");
        assertTrue(result.contains("\"success\":false"));
        assertFalse(rest.commitCalled);
    }

    @Test
    void autoResolveAll_withConfirmationRequired_stagesAllFixesWithThreadIds() {
        PRReviewTools tools = newTools(true);
        String fixesJson = """
                [{"threadId":"RT_1","filePath":"Foo.java","newContent":"fixed a","commitMessage":"fix: a"},
                 {"threadId":"RT_2","filePath":"Bar.java","newContent":"fixed b","commitMessage":"fix: b"}]
                """;

        String result = tools.autoResolveAll("octocat", "hello-world", "main", fixesJson);

        assertTrue(result.contains("\"staged\":true"));
        assertFalse(rest.commitCalled);
        assertFalse(graphQL.resolveCalled);
        assertEquals(2, tools.pendingFixes.listAll().size());
    }

    @Test
    void confirmAllPendingFixes_commitsAndResolvesEveryStagedFix() {
        PRReviewTools tools = newTools(true);
        String fixesJson = """
                [{"threadId":"RT_1","filePath":"Foo.java","newContent":"fixed a","commitMessage":"fix: a"},
                 {"threadId":"RT_2","filePath":"Bar.java","newContent":"fixed b","commitMessage":"fix: b"}]
                """;
        tools.autoResolveAll("octocat", "hello-world", "main", fixesJson);

        String result = tools.confirmAllPendingFixes();

        assertTrue(result.contains("2/2 staged fixes committed"));
        assertTrue(rest.commitCalled);
        assertTrue(graphQL.resolveCalled);
        assertEquals(0, tools.pendingFixes.listAll().size());
    }

    @Test
    void autoResolveAll_withoutConfirmationRequired_commitsAndResolvesImmediately() {
        PRReviewTools tools = newTools(false);
        String fixesJson = """
                [{"threadId":"RT_1","filePath":"Foo.java","newContent":"fixed a","commitMessage":"fix: a"}]
                """;

        String result = tools.autoResolveAll("octocat", "hello-world", "main", fixesJson);

        assertTrue(result.contains("1/1 fixes applied and threads resolved"));
        assertTrue(rest.commitCalled);
        assertTrue(graphQL.resolveCalled);
        assertEquals(0, tools.pendingFixes.listAll().size());
    }
}
