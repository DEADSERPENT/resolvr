package com.resolvr.resolution;

import com.resolvr.github.GitHubGraphQLClient;
import com.resolvr.github.GitHubRestClient;
import com.resolvr.model.PullRequestMetadata;
import com.resolvr.pr.WorkspacePrContextService;
import com.resolvr.workspace.GitStateService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3 — the local-first commit/push layer with a single approval
 * boundary (spec discussion, "99% automation"). Copilot owns understanding
 * the PR, editing the workspace, and running tests — all inside its own
 * Agent Mode turn. This service owns what comes after: turning whatever
 * Copilot left in the working tree into a safe, verified GitHub write.
 *
 * The core safety property: commit_and_push_resolution never trusts what
 * Copilot *says* it changed — it re-derives branch, HEAD, working-tree
 * contents, and remote PR state from Git/GitHub independently before
 * committing or pushing anything.
 */
@ApplicationScoped
public class ResolutionService {

    @Inject
    WorkspacePrContextService workspacePrContext;

    @Inject
    GitStateService git;

    @Inject
    GitHubRestClient rest;

    @Inject
    GitHubGraphQLClient graphQL;

    @Inject
    ResolutionTaskStore tasks;

    // ─── get_local_changes — read-only ──────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> getLocalChanges(String workspacePath) {
        Map<String, Object> discovery = workspacePrContext.getContext(workspacePath);
        Object workspaceObj = discovery.get("workspace");
        if (workspaceObj == null) {
            return discovery; // not a git repo, or a lower-level git failure — propagate as-is
        }
        Map<String, Object> workspace = (Map<String, Object>) workspaceObj;
        String repoRoot = (String) workspace.get("path");

        List<GitStateService.LocalChange> changes = git.listLocalChanges(repoRoot);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repository", discovery.get("repository"));
        result.put("pullRequest", discovery.get("pullRequest"));
        result.put("branch", workspace.get("branch"));
        result.put("headSha", workspace.get("headSha"));
        result.put("workingTreeClean", changes.isEmpty());
        result.put("files", changes);

        int additions = changes.stream().mapToInt(GitStateService.LocalChange::additions).sum();
        int deletions = changes.stream().mapToInt(GitStateService.LocalChange::deletions).sum();
        Map<String, Object> diffStats = new LinkedHashMap<>();
        diffStats.put("filesChanged", changes.size());
        diffStats.put("additions", additions);
        diffStats.put("deletions", deletions);
        result.put("diffStats", diffStats);

        return result;
    }

    // ─── prepare_resolution_summary — read-only, stages a task for approval ─

    @SuppressWarnings("unchecked")
    public Map<String, Object> prepareResolutionSummary(String workspacePath, String commitMessage,
                                                          List<String> addressedThreadIds) {
        Map<String, Object> discovery = workspacePrContext.getContext(workspacePath);

        Object repositoryObj = discovery.get("repository");
        Object pullRequestObj = discovery.get("pullRequest");
        if (repositoryObj == null || Boolean.TRUE.equals(discovery.get("multipleMatches")) || pullRequestObj == null) {
            return discovery; // discovery didn't resolve one PR unambiguously — don't guess
        }
        if (commitMessage == null || commitMessage.isBlank()) {
            return error("commitMessage is required to prepare a resolution.");
        }

        Map<String, Object> repository = (Map<String, Object>) repositoryObj;
        Map<String, Object> prSummary = (Map<String, Object>) pullRequestObj;
        Map<String, Object> workspace = (Map<String, Object>) discovery.get("workspace");

        String owner = (String) repository.get("owner");
        String name = (String) repository.get("name");
        int number = (Integer) prSummary.get("number");
        String prHeadBranch = (String) prSummary.get("headBranch");
        String prBaseBranch = (String) prSummary.get("baseBranch");
        String prHeadSha = (String) prSummary.get("headSha");
        String localBranch = (String) workspace.get("branch");
        String localHeadSha = (String) workspace.get("headSha");
        String repoRoot = (String) workspace.get("path");

        if (localBranch == null || !localBranch.equals(prHeadBranch)) {
            return error("Current branch '" + localBranch + "' does not match PR #" + number
                    + "'s head branch '" + prHeadBranch + "'. Switch to the PR branch before preparing a resolution.");
        }
        if (localBranch.equals(prBaseBranch)) {
            return error("Current branch '" + localBranch + "' is the PR's base branch — refusing to "
                    + "prepare a resolution against it.");
        }
        if (!localHeadSha.equals(prHeadSha)) {
            return error("Local HEAD (" + localHeadSha + ") differs from the PR's remote HEAD (" + prHeadSha
                    + "). Synchronize the branch (pull/rebase) before preparing a resolution.");
        }

        List<GitStateService.LocalChange> changes = git.listLocalChanges(repoRoot);
        if (changes.isEmpty()) {
            return error("Working tree has no changes — nothing to prepare a resolution for.");
        }

        List<String> files = changes.stream().map(GitStateService.LocalChange::path).toList();
        List<String> threadIds = addressedThreadIds == null ? List.of() : List.copyOf(addressedThreadIds);

        String token = tasks.stage(owner, name, number, localBranch, repoRoot,
                localHeadSha, prHeadSha, files, commitMessage, threadIds);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("repository", repository);
        result.put("pullRequest", prSummary);
        result.put("branch", localBranch);
        result.put("files", changes);

        int additions = changes.stream().mapToInt(GitStateService.LocalChange::additions).sum();
        int deletions = changes.stream().mapToInt(GitStateService.LocalChange::deletions).sum();
        result.put("diffStats", Map.of("filesChanged", changes.size(), "additions", additions, "deletions", deletions));

        result.put("commitMessage", commitMessage);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (String id : threadIds) {
            candidates.add(Map.of("threadId", id, "candidateStatus", "ADDRESSED",
                    "note", "Reported by the agent; not independently verified against the diff."));
        }
        result.put("threadCandidates", candidates);
        result.put("status", ResolutionStatus.READY_FOR_APPROVAL.name());
        result.put("message", "Resolution ready for approval. Nothing has been committed or pushed. "
                + "Call commit_and_push_resolution(token) after the developer approves, "
                + "or discard_resolution(token) to cancel.");
        return result;
    }

    // ─── commit_and_push_resolution — the approval boundary ─────────────────

    public Map<String, Object> commitAndPushResolution(String token) {
        ResolutionTask task = tasks.get(token);
        if (task == null) {
            return error("No resolution with that token — already pushed, discarded, or invalid.");
        }
        if (task.status() != ResolutionStatus.READY_FOR_APPROVAL) {
            return error("Resolution " + token + " is in state " + task.status()
                    + ", not READY_FOR_APPROVAL — it cannot be committed.");
        }

        // Re-derive everything from Git/GitHub. Don't trust what was true when the summary was prepared.
        String currentBranch;
        String currentHeadSha;
        try {
            currentBranch = git.getCurrentBranch(task.repoRoot());
            currentHeadSha = git.getHeadSha(task.repoRoot());
        } catch (GitStateService.GitCommandException e) {
            tasks.update(task.withStatus(ResolutionStatus.FAILED));
            return error("Could not re-verify workspace state: " + e.getMessage());
        }

        if (!task.branch().equals(currentBranch)) {
            tasks.update(task.withStatus(ResolutionStatus.STALE));
            return error("Branch changed since the resolution was prepared (expected '" + task.branch()
                    + "', now on '" + currentBranch + "'). Re-run prepare_resolution_summary.");
        }
        if (!task.expectedLocalHeadSha().equals(currentHeadSha)) {
            tasks.update(task.withStatus(ResolutionStatus.STALE));
            return error("Local HEAD changed since the resolution was prepared. Re-run prepare_resolution_summary.");
        }

        List<GitStateService.LocalChange> currentChanges = git.listLocalChanges(task.repoRoot());
        List<String> currentPaths = currentChanges.stream().map(GitStateService.LocalChange::path).toList();
        if (!currentPaths.containsAll(task.files())) {
            tasks.update(task.withStatus(ResolutionStatus.STALE));
            return error("Some of the approved files are no longer changed in the working tree. "
                    + "Re-run prepare_resolution_summary.");
        }

        PullRequestMetadata freshPr;
        try {
            freshPr = rest.getPullRequest(task.owner(), task.repo(), task.prNumber());
        } catch (Exception e) {
            tasks.update(task.withStatus(ResolutionStatus.FAILED));
            return error("Could not verify the PR is still open on GitHub: " + e.getMessage());
        }
        if (!"open".equalsIgnoreCase(freshPr.state())) {
            tasks.update(task.withStatus(ResolutionStatus.STALE));
            return error("PR #" + task.prNumber() + " is no longer open (state: " + freshPr.state() + ") — refusing to push.");
        }
        if (!task.expectedPrHeadSha().equals(freshPr.headSha())) {
            tasks.update(task.withStatus(ResolutionStatus.STALE));
            return error("The PR branch changed on GitHub since the resolution was prepared "
                    + "(expected HEAD " + task.expectedPrHeadSha() + ", GitHub now has " + freshPr.headSha()
                    + "). Re-run prepare_resolution_summary.");
        }

        try {
            git.stageFiles(task.repoRoot(), task.files());
            String newSha = git.commit(task.repoRoot(), task.commitMessage());
            git.push(task.repoRoot(), task.branch());

            ResolutionTask pushed = task.withStatus(ResolutionStatus.PUSHED);
            tasks.update(pushed);

            Log.infof("Resolution %s: committed %s and pushed to %s/%s@%s",
                    token, newSha, task.owner(), task.repo(), task.branch());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("token", token);
            result.put("commitSha", newSha);
            result.put("branch", task.branch());
            result.put("filesCommitted", task.files());
            result.put("status", ResolutionStatus.PUSHED.name());
            result.put("addressedThreadIds", task.addressedThreadIds());
            result.put("message", "Committed and pushed. Call resolve_addressed_threads(token, threadIds) "
                    + "to close out the threads this resolution addressed.");
            return result;
        } catch (GitStateService.GitCommandException e) {
            tasks.update(task.withStatus(ResolutionStatus.FAILED));
            Log.errorf(e, "commit/push failed for resolution %s", token);
            return error("Commit/push failed: " + e.getMessage()
                    + " — no thread will be resolved for this attempt.");
        }
    }

    // ─── resolve_addressed_threads — only valid after a successful push ────

    public Map<String, Object> resolveAddressedThreads(String token, List<String> threadIds) {
        ResolutionTask task = tasks.get(token);
        if (task == null) {
            return error("No resolution with that token.");
        }
        if (task.status() != ResolutionStatus.PUSHED) {
            return error("Resolution " + token + " is in state " + task.status()
                    + ", not PUSHED — threads are only resolved after a successful push.");
        }

        List<String> ids = (threadIds == null || threadIds.isEmpty()) ? task.addressedThreadIds() : threadIds;
        List<Map<String, Object>> results = new ArrayList<>();
        for (String id : ids) {
            try {
                graphQL.resolveThread(id);
                results.add(Map.of("threadId", id, "resolved", true));
            } catch (Exception e) {
                Log.errorf(e, "Failed to resolve thread %s for resolution %s", id, token);
                results.add(Map.of("threadId", id, "resolved", false, "error", e.getMessage()));
            }
        }

        tasks.update(task.withStatus(ResolutionStatus.THREADS_RESOLVED));

        long resolvedCount = results.stream().filter(r -> Boolean.TRUE.equals(r.get("resolved"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("resolvedCount", resolvedCount);
        result.put("totalCount", ids.size());
        result.put("results", results);
        result.put("status", ResolutionStatus.THREADS_RESOLVED.name());
        return result;
    }

    // ─── discard_resolution ──────────────────────────────────────────────────

    public Map<String, Object> discardResolution(String token) {
        ResolutionTask removed = tasks.remove(token);
        if (removed == null) {
            return Map.of("discarded", false, "error", "No resolution with that token.");
        }
        return Map.of("discarded", true, "token", token);
    }

    private Map<String, Object> error(String message) {
        return Map.of("error", message);
    }
}
