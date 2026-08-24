package com.resolvr.pr;

import com.resolvr.github.GitHubRestClient;
import com.resolvr.model.PullRequestSummary;
import com.resolvr.workspace.GitRemoteParser;
import com.resolvr.workspace.GitStateService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Answers "what am I looking at?" for the default Manual/On-demand mode
 * (spec §6.1, §9.1): workspace → Git remote → owner/repo → current branch →
 * matching open GitHub PR, on demand, with no webhook involved.
 *
 * Read-only. Never switches branches, edits files, commits, pushes, or
 * resolves threads.
 */
@ApplicationScoped
public class WorkspacePrContextService {

    @Inject
    public GitStateService git;

    @Inject
    public GitHubRestClient rest;

    @ConfigProperty(name = "resolvr.workspace.path")
    public Optional<String> configuredWorkspacePath;

    public Map<String, Object> getContext(String requestedWorkspacePath) {
        Map<String, Object> result = new LinkedHashMap<>();
        String workspacePath = resolveWorkspacePath(requestedWorkspacePath);

        String repoRoot;
        try {
            repoRoot = git.findRepoRoot(workspacePath);
        } catch (GitStateService.GitCommandException e) {
            result.put("error", e.getMessage());
            return result;
        }

        String remoteUrl = git.getRemoteUrl(repoRoot, "origin");
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("path", repoRoot);
        workspace.put("remote", remoteUrl);

        String branch;
        try {
            branch = git.getCurrentBranch(repoRoot);
        } catch (GitStateService.GitCommandException e) {
            result.put("workspace", workspace);
            result.put("error", e.getMessage());
            return result;
        }
        boolean detached = branch == null;
        workspace.put("branch", branch);
        workspace.put("detachedHead", detached);

        String headSha;
        try {
            headSha = git.getHeadSha(repoRoot);
        } catch (GitStateService.GitCommandException e) {
            result.put("workspace", workspace);
            result.put("error", e.getMessage());
            return result;
        }
        workspace.put("headSha", headSha);

        boolean clean;
        try {
            clean = git.isWorkingTreeClean(repoRoot);
        } catch (GitStateService.GitCommandException e) {
            clean = false;
            workspace.put("workingTreeStatusError", e.getMessage());
        }
        result.put("workspace", workspace);
        result.put("workingTree", Map.of("clean", clean));

        if (remoteUrl == null) {
            result.put("message", "The repository at " + repoRoot + " has no 'origin' remote configured. "
                    + "Add one pointing at the GitHub repository to enable PR discovery.");
            return result;
        }

        Optional<GitRemoteParser.GitHubRepoRef> repoRef = GitRemoteParser.parse(remoteUrl);
        if (repoRef.isEmpty()) {
            String host = GitRemoteParser.parseHost(remoteUrl).orElse("unrecognized");
            result.put("message", "The 'origin' remote (" + remoteUrl + ") is not a GitHub repository "
                    + "(host: " + host + "). Resolvr only supports github.com remotes.");
            return result;
        }

        GitRemoteParser.GitHubRepoRef ref = repoRef.get();
        result.put("repository", Map.of("owner", ref.owner(), "name", ref.name()));

        if (detached) {
            result.put("message", "HEAD is detached — the workspace is not on any branch. "
                    + "Checkout a branch to associate it with a PR.");
            return result;
        }

        List<PullRequestSummary> matches;
        try {
            matches = rest.listOpenPullRequests(ref.owner(), ref.name(), branch);
        } catch (Exception e) {
            Log.errorf(e, "GitHub PR lookup failed for %s/%s branch %s", ref.owner(), ref.name(), branch);
            result.put("error", "GitHub API request failed: " + e.getMessage());
            result.put("message", "Could not query GitHub for open PRs — the workspace/repository "
                    + "information above is still accurate.");
            return result;
        }

        if (matches.isEmpty()) {
            result.put("pullRequest", null);
            result.put("message", "No open PR found for branch '" + branch + "' in "
                    + ref.owner() + "/" + ref.name() + ".");
            return result;
        }

        if (matches.size() > 1) {
            result.put("multipleMatches", true);
            result.put("candidates", matches);
            result.put("message", matches.size() + " open PRs match branch '" + branch
                    + "' — ask the user which one to use.");
            return result;
        }

        PullRequestSummary pr = matches.get(0);
        Map<String, Object> pullRequest = new LinkedHashMap<>();
        pullRequest.put("number", pr.number());
        pullRequest.put("title", pr.title());
        pullRequest.put("baseBranch", pr.baseBranch());
        pullRequest.put("headBranch", pr.headBranch());
        pullRequest.put("headSha", pr.headSha());
        pullRequest.put("state", pr.state());
        result.put("pullRequest", pullRequest);

        boolean upToDate = headSha.equals(pr.headSha());
        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("upToDate", upToDate);
        sync.put("localHeadSha", headSha);
        sync.put("prHeadSha", pr.headSha());
        sync.put("message", upToDate
                ? "Local branch is in sync with the PR's remote branch."
                : "Local HEAD (" + headSha + ") differs from the PR's remote HEAD (" + pr.headSha() + "). "
                        + "The local branch is behind or has diverged from GitHub — do not perform a remote "
                        + "write until the workspace is synchronized.");
        result.put("sync", sync);

        result.put("message", "Current workspace is associated with PR #" + pr.number() + ".");
        return result;
    }

    private String resolveWorkspacePath(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        if (configuredWorkspacePath.isPresent() && !configuredWorkspacePath.get().isBlank()) {
            return configuredWorkspacePath.get();
        }
        return System.getProperty("user.dir");
    }
}
