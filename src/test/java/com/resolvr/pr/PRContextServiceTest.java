package com.resolvr.pr;

import com.resolvr.github.GitHubGraphQLClient;
import com.resolvr.github.GitHubRestClient;
import com.resolvr.model.ChangedFile;
import com.resolvr.model.CheckRun;
import com.resolvr.model.CommitInfo;
import com.resolvr.model.PrComment;
import com.resolvr.model.PullRequestMetadata;
import com.resolvr.model.ReviewThread;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the PR Context Engine's aggregation logic in isolation from the
 * network and Git boundaries: WorkspacePrContextService, GitHubRestClient,
 * and GitHubGraphQLClient are all faked with canned/controllable
 * subclasses (same pattern as PRReviewToolsConfirmationTest), so these
 * tests focus purely on how PRContextService combines their results.
 */
class PRContextServiceTest {

    static class FakeDiscovery extends WorkspacePrContextService {
        Map<String, Object> toReturn;

        @Override
        public Map<String, Object> getContext(String workspacePath) {
            return toReturn;
        }
    }

    static class FakeRestClient extends GitHubRestClient {
        PullRequestMetadata metadata = new PullRequestMetadata(
                42, "Fix auth", "desc", "open", "octocat", "main", "feature/auth", "prsha", null, null, null);
        RuntimeException metadataError;

        List<PrComment> comments = List.of();
        RuntimeException commentsError;

        List<ChangedFile> changedFiles = List.of();
        RuntimeException changedFilesError;

        String diff = "diff --git a/Foo.java b/Foo.java\n";
        RuntimeException diffError;

        List<CommitInfo> commits = List.of();
        RuntimeException commitsError;

        List<CheckRun> checkRuns = List.of();
        RuntimeException checkRunsError;

        @Override
        public PullRequestMetadata getPullRequest(String owner, String repo, int prNumber) {
            if (metadataError != null) throw metadataError;
            return metadata;
        }

        @Override
        public List<PrComment> listIssueComments(String owner, String repo, int prNumber) {
            if (commentsError != null) throw commentsError;
            return comments;
        }

        @Override
        public List<ChangedFile> listChangedFiles(String owner, String repo, int prNumber) {
            if (changedFilesError != null) throw changedFilesError;
            return changedFiles;
        }

        @Override
        public String getDiff(String owner, String repo, int prNumber) {
            if (diffError != null) throw diffError;
            return diff;
        }

        @Override
        public List<CommitInfo> listCommits(String owner, String repo, int prNumber) {
            if (commitsError != null) throw commitsError;
            return commits;
        }

        @Override
        public List<CheckRun> listCheckRuns(String owner, String repo, String ref) {
            if (checkRunsError != null) throw checkRunsError;
            return checkRuns;
        }
    }

    static class FakeGraphQLClient extends GitHubGraphQLClient {
        List<ReviewThread> threads = List.of();
        RuntimeException threadsError;

        @Override
        public List<ReviewThread> getAllReviewThreads(String owner, String repo, int prNumber) {
            if (threadsError != null) throw threadsError;
            return threads;
        }
    }

    private FakeDiscovery discovery;
    private FakeRestClient rest;
    private FakeGraphQLClient graphQL;
    private PRContextService service;

    @BeforeEach
    void setUp() {
        discovery = new FakeDiscovery();
        rest = new FakeRestClient();
        graphQL = new FakeGraphQLClient();
        service = new PRContextService();
        service.workspacePrContext = discovery;
        service.rest = rest;
        service.graphQL = graphQL;
    }

    private static Map<String, Object> discoveryWithOnePr(String localHeadSha, String prHeadSha) {
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("path", "/repo");
        workspace.put("remote", "https://github.com/octocat/hello-world.git");
        workspace.put("branch", "feature/auth");
        workspace.put("detachedHead", false);
        workspace.put("headSha", localHeadSha);

        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("number", 42);
        pr.put("title", "Fix auth");
        pr.put("baseBranch", "main");
        pr.put("headBranch", "feature/auth");
        pr.put("headSha", prHeadSha);
        pr.put("state", "OPEN");

        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("upToDate", localHeadSha.equals(prHeadSha));
        sync.put("localHeadSha", localHeadSha);
        sync.put("prHeadSha", prHeadSha);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("repository", Map.of("owner", "octocat", "name", "hello-world"));
        ctx.put("workspace", workspace);
        ctx.put("workingTree", Map.of("clean", true));
        ctx.put("pullRequest", pr);
        ctx.put("sync", sync);
        ctx.put("message", "Current workspace is associated with PR #42.");
        return ctx;
    }

    // ─── happy path ──────────────────────────────────────────────────────────

    @Test
    void completePrContext_allSectionsPresent() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        rest.comments = List.of(new PrComment("reviewer1", "LGTM", "2026-01-01T00:00:00Z"));
        rest.changedFiles = List.of(new ChangedFile("Foo.java", 5, 1, "modified", "filesha"));
        rest.commits = List.of(new CommitInfo("c1", "fix: x", "Ada", "2026-01-01T00:00:00Z"));
        graphQL.threads = List.of(threadOf("RT_1", false));
        rest.checkRuns = List.of(new CheckRun(1L, "unit-tests", "completed", "success", null));

        Map<String, Object> ctx = service.getContext("/repo");

        assertNotNull(ctx.get("pullRequest"));
        assertNotNull(ctx.get("reviewThreads"));
        assertNotNull(ctx.get("comments"));
        assertNotNull(ctx.get("changedFiles"));
        assertNotNull(ctx.get("diff"));
        assertNotNull(ctx.get("commits"));
        assertNotNull(ctx.get("ci"));
        assertEquals(Map.of("owner", "octocat", "name", "hello-world"), ctx.get("repository"));
    }

    // ─── review threads ──────────────────────────────────────────────────────

    @Test
    void unresolvedThreads_reflectedInCount() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        graphQL.threads = List.of(threadOf("RT_1", false), threadOf("RT_2", false));

        Map<String, Object> ctx = service.getContext("/repo");

        @SuppressWarnings("unchecked")
        Map<String, Object> reviewThreads = (Map<String, Object>) ctx.get("reviewThreads");
        assertEquals(2L, reviewThreads.get("unresolvedCount"));
        assertEquals(2, reviewThreads.get("totalCount"));
    }

    @Test
    void resolvedThreads_excludedFromUnresolvedCount() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        graphQL.threads = List.of(threadOf("RT_1", false), threadOf("RT_2", true));

        Map<String, Object> ctx = service.getContext("/repo");

        @SuppressWarnings("unchecked")
        Map<String, Object> reviewThreads = (Map<String, Object>) ctx.get("reviewThreads");
        assertEquals(1L, reviewThreads.get("unresolvedCount"));
        assertEquals(2, reviewThreads.get("totalCount"));
    }

    @Test
    void multipleReviewThreads_allReturned() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        graphQL.threads = List.of(threadOf("RT_1", false), threadOf("RT_2", true), threadOf("RT_3", false));

        Map<String, Object> ctx = service.getContext("/repo");

        @SuppressWarnings("unchecked")
        Map<String, Object> reviewThreads = (Map<String, Object>) ctx.get("reviewThreads");
        @SuppressWarnings("unchecked")
        List<ReviewThread> list = (List<ReviewThread>) reviewThreads.get("threads");
        assertEquals(3, list.size());
    }

    private static ReviewThread threadOf(String id, boolean resolved) {
        return new ReviewThread(id, "Foo.java", 10, "comment", "github-copilot",
                "feature/auth", "octocat", "hello-world", 42, resolved);
    }

    // ─── changed files / commits ─────────────────────────────────────────────

    @Test
    void changedFiles_fieldsMappedThrough() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        rest.changedFiles = List.of(new ChangedFile("Foo.java", 5, 1, "modified", "filesha"));

        Map<String, Object> ctx = service.getContext("/repo");

        @SuppressWarnings("unchecked")
        List<ChangedFile> files = (List<ChangedFile>) ctx.get("changedFiles");
        assertEquals("Foo.java", files.get(0).path());
        assertEquals(5, files.get(0).additions());
    }

    @Test
    void commits_fieldsMappedThrough() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        rest.commits = List.of(new CommitInfo("c1", "fix: x", "Ada", "2026-01-01T00:00:00Z"));

        Map<String, Object> ctx = service.getContext("/repo");

        @SuppressWarnings("unchecked")
        List<CommitInfo> commits = (List<CommitInfo>) ctx.get("commits");
        assertEquals("c1", commits.get(0).sha());
        assertEquals("fix: x", commits.get(0).message());
    }

    // ─── CI status ───────────────────────────────────────────────────────────

    @Test
    void ciAllSuccess_overallStatusPassing() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        rest.checkRuns = List.of(new CheckRun(1L, "unit-tests", "completed", "success", null));

        Map<String, Object> ctx = service.getContext("/repo");

        @SuppressWarnings("unchecked")
        Map<String, Object> ci = (Map<String, Object>) ctx.get("ci");
        assertEquals("PASSING", ci.get("overallStatus"));
    }

    @Test
    void ciOneFailure_overallStatusFailing() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        rest.checkRuns = List.of(
                new CheckRun(1L, "unit-tests", "completed", "success", null),
                new CheckRun(2L, "build", "completed", "failure", null));

        Map<String, Object> ctx = service.getContext("/repo");

        @SuppressWarnings("unchecked")
        Map<String, Object> ci = (Map<String, Object>) ctx.get("ci");
        assertEquals("FAILING", ci.get("overallStatus"));
    }

    @Test
    void ciInProgress_overallStatusPending() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        rest.checkRuns = List.of(new CheckRun(1L, "unit-tests", "in_progress", null, null));

        Map<String, Object> ctx = service.getContext("/repo");

        @SuppressWarnings("unchecked")
        Map<String, Object> ci = (Map<String, Object>) ctx.get("ci");
        assertEquals("PENDING", ci.get("overallStatus"));
    }

    // ─── empty review comments ───────────────────────────────────────────────

    @Test
    void emptyReviewComments_returnsEmptyList() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        rest.comments = List.of();

        Map<String, Object> ctx = service.getContext("/repo");

        assertTrue(((List<?>) ctx.get("comments")).isEmpty());
    }

    // ─── GitHub failures — partial context ──────────────────────────────────

    @Test
    void oneGitHubSectionFails_othersStillReturned() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        rest.diffError = new RuntimeException("GitHub returned 503");
        rest.changedFiles = List.of(new ChangedFile("Foo.java", 1, 0, "modified", "sha"));

        Map<String, Object> ctx = service.getContext("/repo");

        @SuppressWarnings("unchecked")
        Map<String, Object> diffSection = (Map<String, Object>) ctx.get("diff");
        assertTrue(diffSection.get("error").toString().contains("503"));
        assertFalse(((List<?>) ctx.get("changedFiles")).isEmpty(), "unaffected sections must still populate");
    }

    @Test
    void multipleGitHubSectionsFail_eachReportsItsOwnError() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        rest.diffError = new RuntimeException("diff failed");
        rest.commitsError = new RuntimeException("commits failed");
        graphQL.threadsError = new RuntimeException("threads failed");

        Map<String, Object> ctx = service.getContext("/repo");

        assertTrue(((Map<?, ?>) ctx.get("diff")).get("error").toString().contains("diff failed"));
        assertTrue(((Map<?, ?>) ctx.get("commits")).get("error").toString().contains("commits failed"));
        assertTrue(((Map<?, ?>) ctx.get("reviewThreads")).get("error").toString().contains("threads failed"));
        assertNotNull(ctx.get("pullRequest"), "sections that didn't fail must still be present");
    }

    @Test
    void metadataFails_fallsBackToDiscoverySummaryPlusError() {
        discovery.toReturn = discoveryWithOnePr("sha1", "sha1");
        rest.metadataError = new RuntimeException("metadata fetch failed");

        Map<String, Object> ctx = service.getContext("/repo");

        @SuppressWarnings("unchecked")
        Map<String, Object> pr = (Map<String, Object>) ctx.get("pullRequest");
        assertEquals(42, pr.get("number"), "discovery's summary fields must survive as a fallback");
        assertTrue(pr.get("error").toString().contains("metadata fetch failed"));
    }

    // ─── sync / workspace pass-through ───────────────────────────────────────

    @Test
    void localRemoteShaMismatch_passedThroughFromDiscovery() {
        discovery.toReturn = discoveryWithOnePr("local-sha", "remote-sha");

        Map<String, Object> ctx = service.getContext("/repo");

        @SuppressWarnings("unchecked")
        Map<String, Object> sync = (Map<String, Object>) ctx.get("sync");
        assertEquals(false, sync.get("upToDate"));
        assertEquals("local-sha", sync.get("localHeadSha"));
        assertEquals("remote-sha", sync.get("prHeadSha"));
    }

    @Test
    void cleanWorkspace_passedThroughFromDiscovery() {
        Map<String, Object> disc = discoveryWithOnePr("sha1", "sha1");
        disc.put("workingTree", Map.of("clean", true));
        discovery.toReturn = disc;

        Map<String, Object> ctx = service.getContext("/repo");

        assertEquals(Map.of("clean", true), ctx.get("workingTree"));
    }

    @Test
    void dirtyWorkspace_passedThroughFromDiscovery() {
        Map<String, Object> disc = discoveryWithOnePr("sha1", "sha1");
        disc.put("workingTree", Map.of("clean", false));
        discovery.toReturn = disc;

        Map<String, Object> ctx = service.getContext("/repo");

        assertEquals(Map.of("clean", false), ctx.get("workingTree"));
    }

    // ─── ambiguous / absent discovery — must not guess ──────────────────────

    @Test
    void discoveryHasMultipleMatches_returnsDiscoveryUnchangedWithoutGitHubCalls() {
        Map<String, Object> disc = new LinkedHashMap<>();
        disc.put("repository", Map.of("owner", "octocat", "name", "hello-world"));
        disc.put("multipleMatches", true);
        disc.put("candidates", List.of(Map.of("number", 1), Map.of("number", 2)));
        discovery.toReturn = disc;

        Map<String, Object> ctx = service.getContext("/repo");

        assertSame(disc, ctx);
    }

    @Test
    void discoveryHasNoMatchingPr_returnsDiscoveryUnchanged() {
        Map<String, Object> disc = new LinkedHashMap<>();
        disc.put("repository", Map.of("owner", "octocat", "name", "hello-world"));
        disc.put("pullRequest", null);
        disc.put("message", "No open PR found for branch 'feature/x'.");
        discovery.toReturn = disc;

        Map<String, Object> ctx = service.getContext("/repo");

        assertSame(disc, ctx);
    }

    @Test
    void discoveryHasNoRepository_returnsDiscoveryUnchanged() {
        Map<String, Object> disc = new LinkedHashMap<>();
        disc.put("message", "The repository has no 'origin' remote configured.");
        discovery.toReturn = disc;

        Map<String, Object> ctx = service.getContext("/repo");

        assertSame(disc, ctx);
    }
}
