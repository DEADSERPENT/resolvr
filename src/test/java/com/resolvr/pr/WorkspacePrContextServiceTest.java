package com.resolvr.pr;

import com.resolvr.github.GitHubRestClient;
import com.resolvr.model.PullRequestSummary;
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
 * Orchestration tests for get_workspace_pr_context: real temp Git repos
 * exercise GitStateService for real, while a fake GitHubRestClient subclass
 * stands in for the network boundary.
 */
class WorkspacePrContextServiceTest {

    static class FakeRestClient extends GitHubRestClient {
        List<PullRequestSummary> toReturn = List.of();
        RuntimeException toThrow;
        boolean called = false;
        String lastOwner, lastRepo, lastBranch;

        @Override
        public List<PullRequestSummary> listOpenPullRequests(String owner, String repo, String headBranch) {
            called = true;
            lastOwner = owner;
            lastRepo = repo;
            lastBranch = headBranch;
            if (toThrow != null) throw toThrow;
            return toReturn;
        }
    }

    @TempDir
    Path tempDir;

    private File repo;
    private FakeRestClient rest;
    private WorkspacePrContextService service;

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
        service = new WorkspacePrContextService();
        service.git = new GitStateService();
        service.rest = rest;
        service.configuredWorkspacePath = Optional.empty();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> context() {
        return service.getContext(repo.getAbsolutePath());
    }

    // ─── happy path ──────────────────────────────────────────────────────────

    @Test
    void onePrMatches_localInSync_returnsFullContext() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/DEADSERPENT/resolvr.git");
        String sha = headSha(repo);
        rest.toReturn = List.of(new PullRequestSummary(42, "Fix auth", "main", "feature/auth", sha, "open", null));

        Map<String, Object> ctx = context();

        assertEquals(Map.of("owner", "DEADSERPENT", "name", "resolvr"), ctx.get("repository"));
        assertTrue(rest.called);
        assertEquals("feature/auth", rest.lastBranch);

        @SuppressWarnings("unchecked")
        Map<String, Object> pr = (Map<String, Object>) ctx.get("pullRequest");
        assertEquals(42, pr.get("number"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sync = (Map<String, Object>) ctx.get("sync");
        assertEquals(true, sync.get("upToDate"));
    }

    @Test
    void localAheadOfRemote_reportsOutOfSync() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/DEADSERPENT/resolvr.git");
        rest.toReturn = List.of(new PullRequestSummary(42, "Fix auth", "main", "feature/auth", "different-sha", "open", null));

        Map<String, Object> ctx = context();

        @SuppressWarnings("unchecked")
        Map<String, Object> sync = (Map<String, Object>) ctx.get("sync");
        assertEquals(false, sync.get("upToDate"));
        assertEquals("different-sha", sync.get("prHeadSha"));
    }

    // ─── no match / multiple matches ────────────────────────────────────────

    @Test
    void noMatchingPr_returnsNullPullRequestWithMessage() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/DEADSERPENT/resolvr.git");
        rest.toReturn = List.of();

        Map<String, Object> ctx = context();

        assertTrue(ctx.containsKey("pullRequest"));
        assertNull(ctx.get("pullRequest"));
        assertTrue(((String) ctx.get("message")).contains("No open PR found"));
    }

    @Test
    void multipleMatchingPrs_returnsCandidatesInsteadOfChoosing() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/DEADSERPENT/resolvr.git");
        rest.toReturn = List.of(
                new PullRequestSummary(42, "A", "main", "feature/auth", "a1", "open", null),
                new PullRequestSummary(43, "B", "develop", "feature/auth", "a1", "open", null));

        Map<String, Object> ctx = context();

        assertEquals(true, ctx.get("multipleMatches"));
        assertFalse(ctx.containsKey("pullRequest"));
        assertEquals(2, ((List<?>) ctx.get("candidates")).size());
    }

    // ─── GitHub failure ──────────────────────────────────────────────────────

    @Test
    void gitHubApiFailure_reportsErrorButKeepsWorkspaceInfo() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/DEADSERPENT/resolvr.git");
        rest.toThrow = new RuntimeException("GitHub returned 503");

        Map<String, Object> ctx = context();

        assertTrue(((String) ctx.get("error")).contains("503"));
        assertNotNull(ctx.get("workspace"), "workspace info gathered before the GitHub call must still be returned");
    }

    // ─── remote edge cases ───────────────────────────────────────────────────

    @Test
    void noOriginRemote_reportsClearlyWithoutCallingGitHub() {
        Map<String, Object> ctx = context();

        assertFalse(rest.called);
        assertFalse(ctx.containsKey("repository"));
        assertTrue(((String) ctx.get("message")).contains("no 'origin' remote"));
    }

    @Test
    void nonGitHubRemote_reportsHostWithoutCallingGitHub() throws Exception {
        runGit(repo, "remote", "add", "origin", "git@gitlab.com:acme/example.git");

        Map<String, Object> ctx = context();

        assertFalse(rest.called);
        assertFalse(ctx.containsKey("repository"));
        assertTrue(((String) ctx.get("message")).contains("gitlab.com"));
    }

    // ─── detached HEAD ───────────────────────────────────────────────────────

    @Test
    void detachedHead_reportsWithoutCallingGitHub() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/DEADSERPENT/resolvr.git");
        String sha = headSha(repo);
        runGit(repo, "checkout", sha);

        Map<String, Object> ctx = context();

        assertFalse(rest.called, "must not query GitHub when there's no branch to match against a PR head");
        @SuppressWarnings("unchecked")
        Map<String, Object> workspace = (Map<String, Object>) ctx.get("workspace");
        assertNull(workspace.get("branch"));
        assertEquals(true, workspace.get("detachedHead"));
        assertTrue(((String) ctx.get("message")).contains("detached"));
    }

    // ─── not a git repository ────────────────────────────────────────────────

    @Test
    void notAGitRepository_returnsErrorOnly() throws Exception {
        File notARepo = tempDir.resolve("not-a-repo").toFile();
        notARepo.mkdirs();

        Map<String, Object> ctx = service.getContext(notARepo.getAbsolutePath());

        assertTrue(ctx.containsKey("error"));
        assertFalse(ctx.containsKey("workspace"));
        assertFalse(rest.called);
    }

    // ─── working tree state surfaces through ────────────────────────────────

    @Test
    void dirtyWorkingTree_reflectedInWorkingTreeClean() throws Exception {
        Files.writeString(repo.toPath().resolve("README.md"), "changed\n");

        Map<String, Object> ctx = context();

        @SuppressWarnings("unchecked")
        Map<String, Object> workingTree = (Map<String, Object>) ctx.get("workingTree");
        assertEquals(false, workingTree.get("clean"));
    }
}
