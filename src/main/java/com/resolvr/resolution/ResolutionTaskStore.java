package com.resolvr.resolution;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory store for prepared resolutions, keyed by an opaque token — same pattern as PendingFixStore. */
@ApplicationScoped
public class ResolutionTaskStore {

    private final Map<String, ResolutionTask> tasks = new ConcurrentHashMap<>();

    public String stage(String owner, String repo, int prNumber, String branch, String repoRoot,
                         String expectedLocalHeadSha, String expectedPrHeadSha,
                         List<String> files, String commitMessage, List<String> addressedThreadIds) {
        String token = "res_" + UUID.randomUUID();
        ResolutionTask task = new ResolutionTask(token, owner, repo, prNumber, branch, repoRoot,
                expectedLocalHeadSha, expectedPrHeadSha, files, commitMessage, addressedThreadIds,
                ResolutionStatus.READY_FOR_APPROVAL, Instant.now());
        tasks.put(token, task);
        return token;
    }

    public ResolutionTask get(String token) {
        return tasks.get(token);
    }

    public void update(ResolutionTask task) {
        tasks.put(task.token(), task);
    }

    public ResolutionTask remove(String token) {
        return tasks.remove(token);
    }
}
