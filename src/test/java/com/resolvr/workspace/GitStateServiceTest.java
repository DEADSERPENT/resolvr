package com.resolvr.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises GitStateService against real, disposable Git repositories created
 * under a JUnit @TempDir — this is local repository introspection, so there's
 * no HTTP boundary to stub; shelling out to the real `git` binary is the most
 * faithful way to verify it.
 */
class GitStateServiceTest {

    @TempDir
    Path tempDir;

    private final GitStateService service = new GitStateService();

    private File repo;

    @BeforeEach
    void initRepo() throws Exception {
        repo = tempDir.resolve("repo").toFile();
        repo.mkdirs();
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "user.name", "Test");
        Files.writeString(repo.toPath().resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");
    }

    private static void runGit(File dir, String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(10, TimeUnit.SECONDS);
        assertTrue(finished, "git command timed out: " + String.join(" ", args));
        assertEquals(0, p.exitValue(), "git " + String.join(" ", args) + " failed: " + output);
    }

    // ─── repo root / not a repo ─────────────────────────────────────────────

    @Test
    void findRepoRoot_validRepo_returnsRoot() throws IOException {
        String root = service.findRepoRoot(repo.getAbsolutePath());
        String expected = normalize(repo.getCanonicalPath());
        assertEquals(expected.toLowerCase(), normalize(root).toLowerCase());
    }

    @Test
    void findRepoRoot_notAGitRepository_throwsClearError() throws IOException {
        File notARepo = tempDir.resolve("not-a-repo").toFile();
        notARepo.mkdirs();

        GitStateService.GitCommandException ex = assertThrows(GitStateService.GitCommandException.class,
                () -> service.findRepoRoot(notARepo.getAbsolutePath()));

        assertTrue(ex.getMessage().contains("not inside a Git repository"), ex.getMessage());
    }

    // ─── remote ──────────────────────────────────────────────────────────────

    @Test
    void getRemoteUrl_noOriginConfigured_returnsNull() {
        assertNull(service.getRemoteUrl(repo.getAbsolutePath(), "origin"));
    }

    @Test
    void getRemoteUrl_httpsGitHubRemote_returnsConfiguredUrl() throws Exception {
        runGit(repo, "remote", "add", "origin", "https://github.com/DEADSERPENT/resolvr.git");
        assertEquals("https://github.com/DEADSERPENT/resolvr.git",
                service.getRemoteUrl(repo.getAbsolutePath(), "origin"));
    }

    @Test
    void getRemoteUrl_sshGitHubRemote_returnsConfiguredUrl() throws Exception {
        runGit(repo, "remote", "add", "origin", "git@github.com:DEADSERPENT/resolvr.git");
        assertEquals("git@github.com:DEADSERPENT/resolvr.git",
                service.getRemoteUrl(repo.getAbsolutePath(), "origin"));
    }

    // ─── branch / HEAD ───────────────────────────────────────────────────────

    @Test
    void getCurrentBranch_onBranch_returnsBranchName() {
        assertEquals("main", service.getCurrentBranch(repo.getAbsolutePath()));
    }

    @Test
    void getCurrentBranch_detachedHead_returnsNull() throws Exception {
        String sha = capture(repo, "rev-parse", "HEAD");
        runGit(repo, "checkout", sha);

        assertNull(service.getCurrentBranch(repo.getAbsolutePath()));
    }

    @Test
    void getHeadSha_matchesGitRevParse() throws Exception {
        String expected = capture(repo, "rev-parse", "HEAD");
        assertEquals(expected, service.getHeadSha(repo.getAbsolutePath()));
    }

    // ─── working tree ────────────────────────────────────────────────────────

    @Test
    void isWorkingTreeClean_noModifications_true() {
        assertTrue(service.isWorkingTreeClean(repo.getAbsolutePath()));
    }

    @Test
    void isWorkingTreeClean_uncommittedModification_false() throws Exception {
        Files.writeString(repo.toPath().resolve("README.md"), "changed\n");
        assertFalse(service.isWorkingTreeClean(repo.getAbsolutePath()));
    }

    @Test
    void isWorkingTreeClean_untrackedFile_false() throws Exception {
        Files.writeString(repo.toPath().resolve("new-file.txt"), "new\n");
        assertFalse(service.isWorkingTreeClean(repo.getAbsolutePath()));
    }

    // ─── listLocalChanges ────────────────────────────────────────────────────

    @Test
    void listLocalChanges_cleanTree_returnsEmpty() {
        assertTrue(service.listLocalChanges(repo.getAbsolutePath()).isEmpty());
    }

    @Test
    void listLocalChanges_modifiedTrackedFile_reportsStatusAndStats() throws Exception {
        Files.writeString(repo.toPath().resolve("README.md"), "hello\nmore text\n");

        var changes = service.listLocalChanges(repo.getAbsolutePath());

        assertEquals(1, changes.size());
        assertEquals("README.md", changes.get(0).path());
        assertEquals("MODIFIED", changes.get(0).status());
        assertEquals(1, changes.get(0).additions());
    }

    @Test
    void listLocalChanges_untrackedFile_reportsNewStatus() throws Exception {
        Files.writeString(repo.toPath().resolve("new-file.txt"), "new\n");

        var changes = service.listLocalChanges(repo.getAbsolutePath());

        assertEquals(1, changes.size());
        assertEquals("new-file.txt", changes.get(0).path());
        assertEquals("NEW", changes.get(0).status());
    }

    @Test
    void listLocalChanges_untrackedDirectory_listsEachFileIndividually() throws Exception {
        // Real bug found via smoke testing: plain `git status --porcelain` collapses a
        // brand-new directory into one "?? path/" entry instead of listing the files
        // inside it, which would undercount the approval package.
        File newPackage = repo.toPath().resolve("newpkg").toFile();
        newPackage.mkdirs();
        Files.writeString(newPackage.toPath().resolve("A.java"), "class A {}\n");
        Files.writeString(newPackage.toPath().resolve("B.java"), "class B {}\n");

        var changes = service.listLocalChanges(repo.getAbsolutePath());

        assertEquals(2, changes.size(), "each file inside the new directory must be listed individually");
        assertTrue(changes.stream().anyMatch(c -> c.path().equals("newpkg/A.java") && c.status().equals("NEW")));
        assertTrue(changes.stream().anyMatch(c -> c.path().equals("newpkg/B.java") && c.status().equals("NEW")));
    }

    @Test
    void listLocalChanges_deletedTrackedFile_reportsDeletedStatus() throws Exception {
        repo.toPath().resolve("README.md").toFile().delete();

        var changes = service.listLocalChanges(repo.getAbsolutePath());

        assertEquals(1, changes.size());
        assertEquals("README.md", changes.get(0).path());
        assertEquals("DELETED", changes.get(0).status());
    }

    // ─── stageFiles / commit / push ──────────────────────────────────────────

    @Test
    void stageFiles_emptyList_throws() {
        assertThrows(GitStateService.GitCommandException.class,
                () -> service.stageFiles(repo.getAbsolutePath(), java.util.List.of()));
    }

    @Test
    void stageCommitPush_roundTrip_remoteReceivesCommit() throws Exception {
        File bareRemote = tempDir.resolve("origin.git").toFile();
        runGit(tempDir.toFile(), "init", "--bare", bareRemote.getAbsolutePath());
        runGit(repo, "remote", "add", "origin", bareRemote.getAbsolutePath());
        runGit(repo, "push", "-u", "origin", "main");

        Files.writeString(repo.toPath().resolve("README.md"), "hello\nfixed\n");
        Files.writeString(repo.toPath().resolve("New.java"), "class New {}\n");

        service.stageFiles(repo.getAbsolutePath(), java.util.List.of("README.md", "New.java"));
        String newSha = service.commit(repo.getAbsolutePath(), "fix: address review feedback");
        service.push(repo.getAbsolutePath(), "main");

        assertEquals(newSha, service.getHeadSha(repo.getAbsolutePath()));
        assertTrue(service.isWorkingTreeClean(repo.getAbsolutePath()), "commit must include all staged files");

        String remoteHead = capture(bareRemote, "rev-parse", "refs/heads/main");
        assertEquals(newSha, remoteHead, "the bare 'origin' remote must have received the new commit");
    }

    @Test
    void stageFiles_onlyStagesGivenPaths_leavesOthersUncommitted() throws Exception {
        Files.writeString(repo.toPath().resolve("README.md"), "approved change\n");
        Files.writeString(repo.toPath().resolve("Unrelated.txt"), "developer's own unrelated edit\n");

        service.stageFiles(repo.getAbsolutePath(), java.util.List.of("README.md"));
        service.commit(repo.getAbsolutePath(), "fix: approved change only");

        var remaining = service.listLocalChanges(repo.getAbsolutePath());
        assertEquals(1, remaining.size(), "the unrelated file must not have been swept into the commit");
        assertEquals("Unrelated.txt", remaining.get(0).path());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static String capture(File dir, String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir).start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(10, TimeUnit.SECONDS);
        return output;
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }
}
