package com.resolvr.resolution;

import java.time.Instant;
import java.util.List;

/**
 * A prepared-but-not-yet-pushed resolution: which local files are being
 * committed, what the workspace looked like when the approval package was
 * built, and what commit_and_push_resolution must re-verify is still true
 * before it trusts any of it. Immutable — state transitions produce a new
 * instance via withStatus, mirroring the rest of the codebase's record style.
 */
public record ResolutionTask(
        String token,
        String owner,
        String repo,
        int prNumber,
        String branch,
        String repoRoot,
        String expectedLocalHeadSha,
        String expectedPrHeadSha,
        List<String> files,
        String commitMessage,
        List<String> addressedThreadIds,
        ResolutionStatus status,
        Instant createdAt
) {
    public ResolutionTask withStatus(ResolutionStatus newStatus) {
        return new ResolutionTask(token, owner, repo, prNumber, branch, repoRoot,
                expectedLocalHeadSha, expectedPrHeadSha, files, commitMessage, addressedThreadIds,
                newStatus, createdAt);
    }
}
