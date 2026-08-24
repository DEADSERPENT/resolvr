package com.resolvr.resolution;

import com.resolvr.github.GitHubGraphQLClient;
import com.resolvr.github.GitHubRestClient;
import com.resolvr.model.PullRequestMetadata;
import com.resolvr.model.PullRequestSummary;
import com.resolvr.pr.WorkspacePrContextService;
import com.resolvr.workspace.GitStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 safety tests. prepare_resolution_summary is exercised against a
 * real temp Git repo through the real discovery chain (so branch/HEAD/PR
 * matching is genuine, not mocked). commit_and_push_resolution is exercised
 * against a repo with a real local bare-repo "origin" it can actually push
 * to, with tasks staged directly (bypassing discovery, which only cares
 * about github.com remotes) so the commit/push safety re-checks are tested
 * against real git state without needing network access.
 */
class ResolutionServiceTest {

    static class FakeRestClient extends GitHubRestClient {
        List<PullRequestSummary> openPrs = List.of();
        PullRequestMetadata metadata;
        RuntimeException metadataError;

        @Override
        public List<PullRequestSummary> listOpenPullRequests(String owner, String repo, String headBranch) {
            return openPrs;
        }

        @Override
        public PullRequestMetadata getPullRequest(String owner, String repo, int prNumber) {
            if (metadataError != null) throw metadataError;
            return metadata;
        }
    }

    static class FakeGraphQLClient extends GitHubGraphQLClient {
        List<String> resolvedIds = new java.util.ArrayList<>();
        String failingThreadId;

        @Override
        public void resolveThread(String threadId) {
            if (threadId.equals(failingThreadId)) {
                throw new RuntimeException("permission denied");
            }
            resolvedIds.add(threadId);
        }
    }

    @TempDir
    Path tempDir;

    private File repo;
    private FakeRestClient rest;
    private FakeGraphQLClient graphQL;
    private GitStateService git;
    private ResolutionTaskStore taskStore;
    private ResolutionService service;

    @BeforeEach
    void setUp() throws Exception {
        repo = tempDir.resolve("repo").toFile();
        repo.mkdirs();
        runGit(repo, "init", "-b", "feature/auth");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "user.name", "Test");
        Files.writeString(repo.toPath().resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");

        rest = new FakeRestClient();
        graphQL = new FakeGraphQLClient();
        git = new GitStateService();
        taskStore = new ResolutionTaskStore();

        service = new ResolutionService();
        service.git = git;
        service.rest = rest;
        service.graphQL = graphQL;
        service.tasks = taskStore;

        WorkspacePrContextService discovery = new WorkspacePrContextService();
        discovery.git = git;
        discovery.rest = rest;
        discovery.configuredWorkspacePath = Optional.empty();
        service.workspacePrContext = discovery;
    }

    private static void runGit(File dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        p.waitFor(10, TimeUnit.SECONDS);
        assertEquals(0, p.exitValue(), "git " + String.join(" ", args) + " failed: " + output);
    }

    private static String headSha(File repo) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", "HEAD").directory(repo).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(10, TimeUnit.SECONDS);
        return out;
    }

    // ─── prepare_resolution_summary ──────────────────────────────────────────

    @Test
    void prepare_success_stagesTaskWithApprovalPackage() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/octocat/hello-world.git");
        String sha = headSha(repo);
        rest.openPrs = List.of(new PullRequestSummary(42, "Fix auth", "main", "feature/auth", sha, "open", null));
        Files.writeString(repo.toPath().resolve("README.md"), "changed\n");

        Map<String, Object> result = service.prepareResolutionSummary(
                repo.getAbsolutePath(), "fix: address review feedback", List.of("RT_1"));

        assertNotNull(result.get("token"));
        assertEquals(ResolutionStatus.READY_FOR_APPROVAL.name(), result.get("status"));
        @SuppressWarnings("unchecked")
        List<?> candidates = (List<?>) result.get("threadCandidates");
        assertEquals(1, candidates.size());
    }

    @Test
    void prepare_branchMismatch_refuses() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/octocat/hello-world.git");
        String sha = headSha(repo);
        rest.openPrs = List.of(new PullRequestSummary(42, "Fix auth", "main", "feature/other", sha, "open", null));
        Files.writeString(repo.toPath().resolve("README.md"), "changed\n");

        Map<String, Object> result = service.prepareResolutionSummary(
                repo.getAbsolutePath(), "fix: x", List.of());

        assertTrue(((String) result.get("error")).contains("does not match"));
        assertFalse(result.containsKey("token"), "must not stage a task when the refusal fires");
    }

    @Test
    void prepare_localHeadBehindPrHead_refuses() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/octocat/hello-world.git");
        rest.openPrs = List.of(new PullRequestSummary(42, "Fix auth", "main", "feature/auth", "different-sha", "open", null));
        Files.writeString(repo.toPath().resolve("README.md"), "changed\n");

        Map<String, Object> result = service.prepareResolutionSummary(
                repo.getAbsolutePath(), "fix: x", List.of());

        assertTrue(((String) result.get("error")).contains("differs from the PR's remote HEAD"));
    }

    @Test
    void prepare_cleanWorkingTree_refuses() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/octocat/hello-world.git");
        String sha = headSha(repo);
        rest.openPrs = List.of(new PullRequestSummary(42, "Fix auth", "main", "feature/auth", sha, "open", null));

        Map<String, Object> result = service.prepareResolutionSummary(
                repo.getAbsolutePath(), "fix: x", List.of());

        assertTrue(((String) result.get("error")).contains("nothing to prepare"));
    }

    @Test
    void prepare_missingCommitMessage_refuses() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/octocat/hello-world.git");
        String sha = headSha(repo);
        rest.openPrs = List.of(new PullRequestSummary(42, "Fix auth", "main", "feature/auth", sha, "open", null));
        Files.writeString(repo.toPath().resolve("README.md"), "changed\n");

        Map<String, Object> result = service.prepareResolutionSummary(repo.getAbsolutePath(), "  ", List.of());

        assertTrue(((String) result.get("error")).contains("commitMessage"));
    }

    @Test
    void prepare_noMatchingPr_returnsDiscoveryUnchanged() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/octocat/hello-world.git");
        rest.openPrs = List.of();
        Files.writeString(repo.toPath().resolve("README.md"), "changed\n");

        Map<String, Object> result = service.prepareResolutionSummary(repo.getAbsolutePath(), "fix: x", List.of());

        assertTrue(result.containsKey("pullRequest"));
        assertNull(result.get("pullRequest"));
        assertFalse(result.containsKey("token"));
    }

    // ─── commit_and_push_resolution ──────────────────────────────────────────

    private String stageDirectTask(String branch, String expectedLocalHeadSha, String expectedPrHeadSha,
                                    List<String> files, String message) {
        return taskStore.stage("octocat", "hello-world", 42, branch, repo.getAbsolutePath(),
                expectedLocalHeadSha, expectedPrHeadSha, files, message, List.of("RT_1"));
    }

    private File setUpPushableOrigin() throws Exception {
        File bareRemote = tempDir.resolve("origin.git").toFile();
        runGit(tempDir.toFile(), "init", "--bare", bareRemote.getAbsolutePath());
        runGit(repo, "remote", "add", "origin", bareRemote.getAbsolutePath());
        runGit(repo, "push", "-u", "origin", "feature/auth");
        return bareRemote;
    }

    @Test
    void commitAndPush_success_pushesToRealRemoteAndUpdatesStatus() throws Exception {
        File bareRemote = setUpPushableOrigin();
        String sha = headSha(repo);
        rest.metadata = new PullRequestMetadata(42, "Fix auth", null, "open", null, "main", "feature/auth", sha, null, null, null);
        Files.writeString(repo.toPath().resolve("README.md"), "fixed\n");

        String token = stageDirectTask("feature/auth", sha, sha, List.of("README.md"), "fix: address review feedback");

        Map<String, Object> result = service.commitAndPushResolution(token);

        assertEquals(true, result.get("success"));
        assertEquals(ResolutionStatus.PUSHED.name(), result.get("status"));
        String remoteHead = capture(bareRemote, "rev-parse", "refs/heads/feature/auth");
        assertEquals(result.get("commitSha"), remoteHead);
    }

    @Test
    void commitAndPush_branchChangedSincePrepare_refusesAsStale() throws Exception {
        setUpPushableOrigin();
        String sha = headSha(repo);
        Files.writeString(repo.toPath().resolve("README.md"), "fixed\n");
        String token = stageDirectTask("feature/auth", sha, sha, List.of("README.md"), "fix: x");

        runGit(repo, "checkout", "-b", "feature/other");

        Map<String, Object> result = service.commitAndPushResolution(token);

        assertTrue(((String) result.get("error")).contains("Branch changed"));
        assertEquals(ResolutionStatus.STALE, taskStore.get(token).status());
    }

    @Test
    void commitAndPush_localHeadChangedSincePrepare_refusesAsStale() throws Exception {
        setUpPushableOrigin();
        String sha = headSha(repo);
        String token = stageDirectTask("feature/auth", sha, sha, List.of("README.md"), "fix: x");

        Files.writeString(repo.toPath().resolve("README.md"), "an extra unplanned commit\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "unplanned");

        Map<String, Object> result = service.commitAndPushResolution(token);

        assertTrue(((String) result.get("error")).contains("Local HEAD changed"));
    }

    @Test
    void commitAndPush_approvedFileNoLongerChanged_refusesAsStale() throws Exception {
        setUpPushableOrigin();
        String sha = headSha(repo);
        rest.metadata = new PullRequestMetadata(42, "Fix auth", null, "open", null, "main", "feature/auth", sha, null, null, null);
        String token = stageDirectTask("feature/auth", sha, sha, List.of("README.md"), "fix: x");
        // README.md was never actually modified in the working tree

        Map<String, Object> result = service.commitAndPushResolution(token);

        assertTrue(((String) result.get("error")).contains("no longer changed"));
    }

    @Test
    void commitAndPush_prNoLongerOpen_refuses() throws Exception {
        setUpPushableOrigin();
        String sha = headSha(repo);
        rest.metadata = new PullRequestMetadata(42, "Fix auth", null, "closed", null, "main", "feature/auth", sha, null, null, null);
        Files.writeString(repo.toPath().resolve("README.md"), "fixed\n");
        String token = stageDirectTask("feature/auth", sha, sha, List.of("README.md"), "fix: x");

        Map<String, Object> result = service.commitAndPushResolution(token);

        assertTrue(((String) result.get("error")).contains("no longer open"));
    }

    @Test
    void commitAndPush_prHeadMovedOnGitHub_refusesAsStale() throws Exception {
        setUpPushableOrigin();
        String sha = headSha(repo);
        rest.metadata = new PullRequestMetadata(42, "Fix auth", null, "open", null, "main", "feature/auth", "someone-else-pushed", null, null, null);
        Files.writeString(repo.toPath().resolve("README.md"), "fixed\n");
        String token = stageDirectTask("feature/auth", sha, sha, List.of("README.md"), "fix: x");

        Map<String, Object> result = service.commitAndPushResolution(token);

        assertTrue(((String) result.get("error")).contains("changed on GitHub"));
    }

    @Test
    void commitAndPush_onlyStagesApprovedFiles_leavesUnrelatedChangeUncommitted() throws Exception {
        setUpPushableOrigin();
        String sha = headSha(repo);
        rest.metadata = new PullRequestMetadata(42, "Fix auth", null, "open", null, "main", "feature/auth", sha, null, null, null);
        Files.writeString(repo.toPath().resolve("README.md"), "approved change\n");
        Files.writeString(repo.toPath().resolve("Unrelated.txt"), "developer's own edit\n");
        String token = stageDirectTask("feature/auth", sha, sha, List.of("README.md"), "fix: x");

        service.commitAndPushResolution(token);

        var remaining = git.listLocalChanges(repo.getAbsolutePath());
        assertEquals(1, remaining.size());
        assertEquals("Unrelated.txt", remaining.get(0).path());
    }

    @Test
    void commitAndPush_alreadyPushed_refusesSecondCall() throws Exception {
        setUpPushableOrigin();
        String sha = headSha(repo);
        rest.metadata = new PullRequestMetadata(42, "Fix auth", null, "open", null, "main", "feature/auth", sha, null, null, null);
        Files.writeString(repo.toPath().resolve("README.md"), "fixed\n");
        String token = stageDirectTask("feature/auth", sha, sha, List.of("README.md"), "fix: x");

        service.commitAndPushResolution(token);
        Map<String, Object> secondCall = service.commitAndPushResolution(token);

        assertTrue(((String) secondCall.get("error")).contains("not READY_FOR_APPROVAL"));
    }

    @Test
    void commitAndPush_unknownToken_returnsError() {
        Map<String, Object> result = service.commitAndPushResolution("does-not-exist");
        assertTrue(((String) result.get("error")).contains("No resolution"));
    }

    // ─── resolve_addressed_threads ───────────────────────────────────────────

    @Test
    void resolveThreads_afterSuccessfulPush_resolvesAndUpdatesStatus() throws Exception {
        setUpPushableOrigin();
        String sha = headSha(repo);
        rest.metadata = new PullRequestMetadata(42, "Fix auth", null, "open", null, "main", "feature/auth", sha, null, null, null);
        Files.writeString(repo.toPath().resolve("README.md"), "fixed\n");
        String token = stageDirectTask("feature/auth", sha, sha, List.of("README.md"), "fix: x");
        service.commitAndPushResolution(token);

        Map<String, Object> result = service.resolveAddressedThreads(token, List.of("RT_1", "RT_2"));

        assertEquals(2L, result.get("resolvedCount"));
        assertEquals(ResolutionStatus.THREADS_RESOLVED.name(), result.get("status"));
        assertEquals(ResolutionStatus.THREADS_RESOLVED, taskStore.get(token).status());
    }

    @Test
    void resolveThreads_partialFailure_reportsPerThreadResults() throws Exception {
        setUpPushableOrigin();
        String sha = headSha(repo);
        rest.metadata = new PullRequestMetadata(42, "Fix auth", null, "open", null, "main", "feature/auth", sha, null, null, null);
        Files.writeString(repo.toPath().resolve("README.md"), "fixed\n");
        String token = stageDirectTask("feature/auth", sha, sha, List.of("README.md"), "fix: x");
        service.commitAndPushResolution(token);
        graphQL.failingThreadId = "RT_2";

        Map<String, Object> result = service.resolveAddressedThreads(token, List.of("RT_1", "RT_2"));

        assertEquals(1L, result.get("resolvedCount"));
        assertEquals(2, result.get("totalCount"));
    }

    @Test
    void resolveThreads_beforePush_refuses() {
        String token = stageDirectTask("feature/auth", "sha1", "sha1", List.of("README.md"), "fix: x");

        Map<String, Object> result = service.resolveAddressedThreads(token, List.of("RT_1"));

        assertTrue(((String) result.get("error")).contains("not PUSHED"));
        assertTrue(graphQL.resolvedIds.isEmpty());
    }

    // ─── discard_resolution ──────────────────────────────────────────────────

    @Test
    void discard_removesTaskWithoutCommitting() throws Exception {
        String token = stageDirectTask("feature/auth", "sha1", "sha1", List.of("README.md"), "fix: x");

        Map<String, Object> result = service.discardResolution(token);

        assertEquals(true, result.get("discarded"));
        assertNull(taskStore.get(token));
    }

    @Test
    void discard_unknownToken_returnsFalse() {
        Map<String, Object> result = service.discardResolution("nope");
        assertEquals(false, result.get("discarded"));
    }

    private static String capture(File dir, String... args) throws Exception {
        Process p = new ProcessBuilder(concat("git", args)).directory(dir).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(10, TimeUnit.SECONDS);
        return out;
    }

    private static List<String> concat(String first, String... rest) {
        List<String> list = new java.util.ArrayList<>();
        list.add(first);
        list.addAll(List.of(rest));
        return list;
    }
}
