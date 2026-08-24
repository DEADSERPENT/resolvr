package com.resolvr.workspace;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shells out to the local `git` binary to answer questions about the
 * repository Resolvr is running alongside — repo root, remote, current
 * branch, HEAD sha, working-tree cleanliness. Resolvr runs as a local
 * process next to the IDE (see .vscode/mcp.json → localhost), so this
 * reads the same filesystem the developer is editing in.
 */
@ApplicationScoped
public class GitStateService {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    public static class GitCommandException extends RuntimeException {
        public GitCommandException(String message) {
            super(message);
        }

        public GitCommandException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Top-level directory of the git repository containing workspacePath. Throws if there isn't one. */
    public String findRepoRoot(String workspacePath) {
        GitResult r = run(workspacePath, "rev-parse", "--show-toplevel");
        if (!r.success()) {
            throw new GitCommandException("'" + workspacePath + "' is not inside a Git repository"
                    + (r.stderr().isBlank() ? "" : " (" + r.stderr().trim() + ")"));
        }
        return r.stdout().trim();
    }

    /** The URL configured for the given remote, or null if that remote doesn't exist. */
    public String getRemoteUrl(String repoRoot, String remoteName) {
        GitResult r = run(repoRoot, "remote", "get-url", remoteName);
        if (!r.success()) {
            return null;
        }
        return r.stdout().trim();
    }

    /** Current branch name, or null when HEAD is detached. */
    public String getCurrentBranch(String repoRoot) {
        GitResult r = run(repoRoot, "branch", "--show-current");
        if (!r.success()) {
            throw new GitCommandException("Failed to determine current branch: " + r.stderr().trim());
        }
        String branch = r.stdout().trim();
        return branch.isEmpty() ? null : branch;
    }

    public String getHeadSha(String repoRoot) {
        GitResult r = run(repoRoot, "rev-parse", "HEAD");
        if (!r.success()) {
            throw new GitCommandException("Failed to determine HEAD commit: " + r.stderr().trim());
        }
        return r.stdout().trim();
    }

    public boolean isWorkingTreeClean(String repoRoot) {
        GitResult r = run(repoRoot, "status", "--porcelain");
        if (!r.success()) {
            throw new GitCommandException("Failed to check working tree status: " + r.stderr().trim());
        }
        return r.stdout().isBlank();
    }

    /** One locally changed file: its path, a normalized status, and line-level diff stats vs. HEAD. */
    public record LocalChange(String path, String status, int additions, int deletions) {
    }

    /** Everything currently changed in the working tree (staged or not) relative to HEAD, including untracked files. */
    public List<LocalChange> listLocalChanges(String repoRoot) {
        Map<String, String> statusByPath = parseStatus(repoRoot);
        Map<String, int[]> statsByPath = parseNumstat(repoRoot);
        List<LocalChange> changes = new ArrayList<>();
        for (Map.Entry<String, String> e : statusByPath.entrySet()) {
            int[] stats = statsByPath.getOrDefault(e.getKey(), new int[]{0, 0});
            changes.add(new LocalChange(e.getKey(), e.getValue(), stats[0], stats[1]));
        }
        return changes;
    }

    /** `git add -- <paths>` — stages exactly the given files, never the whole working tree. */
    public void stageFiles(String repoRoot, List<String> paths) {
        if (paths.isEmpty()) {
            throw new GitCommandException("No files given to stage");
        }
        List<String> args = new ArrayList<>();
        args.add("add");
        args.add("--");
        args.addAll(paths);
        GitResult r = run(repoRoot, args.toArray(new String[0]));
        if (!r.success()) {
            throw new GitCommandException("Failed to stage files: " + r.stderr().trim());
        }
    }

    /** Commits whatever is currently staged and returns the new HEAD sha. */
    public String commit(String repoRoot, String message) {
        GitResult r = run(repoRoot, "commit", "-m", message);
        if (!r.success()) {
            throw new GitCommandException("Failed to commit: "
                    + (r.stderr().isBlank() ? r.stdout().trim() : r.stderr().trim()));
        }
        return getHeadSha(repoRoot);
    }

    /** Pushes the given local branch to the `origin` remote. */
    public void push(String repoRoot, String branch) {
        GitResult r = run(repoRoot, "push", "origin", branch);
        if (!r.success()) {
            throw new GitCommandException("Failed to push branch '" + branch + "': " + r.stderr().trim());
        }
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private Map<String, String> parseStatus(String repoRoot) {
        // --untracked-files=all: without it, a brand-new directory collapses into one
        // "?? path/" entry instead of listing each new file inside it individually,
        // which would undercount files/diffStats in the approval package.
        GitResult r = run(repoRoot, "status", "--porcelain", "--untracked-files=all");
        if (!r.success()) {
            throw new GitCommandException("Failed to check working tree status: " + r.stderr().trim());
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : r.stdout().split("\n")) {
            if (line.isBlank() || line.length() < 4) continue;
            String code = line.substring(0, 2);
            String rest = line.substring(3);
            int arrow = rest.indexOf(" -> ");
            String path = arrow >= 0 ? rest.substring(arrow + 4) : rest;
            result.put(path.trim(), normalizeStatus(code));
        }
        return result;
    }

    private static String normalizeStatus(String code) {
        if ("??".equals(code)) return "NEW";
        if (code.contains("A")) return "ADDED";
        if (code.contains("D")) return "DELETED";
        if (code.contains("R")) return "RENAMED";
        return "MODIFIED";
    }

    /** additions/deletions per changed tracked file, relative to HEAD. Untracked files aren't covered by `git diff`. */
    private Map<String, int[]> parseNumstat(String repoRoot) {
        Map<String, int[]> result = new LinkedHashMap<>();
        GitResult r = run(repoRoot, "diff", "HEAD", "--numstat");
        if (!r.success()) {
            return result; // e.g. no commits yet on HEAD — fall back to zero stats; status is still reported
        }
        for (String line : r.stdout().split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t");
            if (parts.length < 3) continue;
            result.put(parts[2].trim(), new int[]{parseIntOrZero(parts[0]), parseIntOrZero(parts[1])});
        }
        return result;
    }

    private static int parseIntOrZero(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record GitResult(int exitCode, String stdout, String stderr) {
        boolean success() {
            return exitCode == 0;
        }
    }

    private GitResult run(String workingDir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(new File(workingDir))
                    .start();
        } catch (IOException e) {
            throw new GitCommandException("Could not run `git " + String.join(" ", args)
                    + "` — is Git installed and on PATH?", e);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread stdoutReader = new Thread(() -> drain(process.getInputStream(), stdout), "git-stdout-reader");
        Thread stderrReader = new Thread(() -> drain(process.getErrorStream(), stderr), "git-stderr-reader");
        stdoutReader.start();
        stderrReader.start();

        try {
            boolean finished = process.waitFor(GIT_TIMEOUT.getSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new GitCommandException("`git " + String.join(" ", args) + "` timed out after "
                        + GIT_TIMEOUT.getSeconds() + "s");
            }
            stdoutReader.join(GIT_TIMEOUT.toMillis());
            stderrReader.join(GIT_TIMEOUT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandException("`git " + String.join(" ", args) + "` was interrupted", e);
        }

        Log.debugf("git %s (exit %d) in %s", String.join(" ", args), process.exitValue(), workingDir);
        return new GitResult(process.exitValue(), stdout.toString(), stderr.toString());
    }

    private static void drain(InputStream in, StringBuilder into) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                into.append(buf, 0, n);
            }
        } catch (IOException ignored) {
            // stream closed when the process exits — nothing further to read
        }
    }
}
