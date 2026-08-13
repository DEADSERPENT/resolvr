package com.resolvr.orchestrator;

import com.resolvr.model.PendingFix;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds fixes staged for review under resolvr.require-confirmation=true.
 * Nothing here reaches GitHub until confirm_fix (or confirm_all_pending_fixes)
 * is called explicitly — that's the whole point of this store.
 */
@ApplicationScoped
public class PendingFixStore {

    private final Map<String, PendingFix> pending = new ConcurrentHashMap<>();

    public String stage(String owner, String repo, String branch, String filePath,
                         String newContent, String commitMessage, String threadId) {
        String token = UUID.randomUUID().toString();
        pending.put(token, new PendingFix(token, owner, repo, branch, filePath,
                newContent, commitMessage, threadId, Instant.now()));
        return token;
    }

    public PendingFix get(String token) {
        return pending.get(token);
    }

    public PendingFix remove(String token) {
        return pending.remove(token);
    }

    public Collection<PendingFix> listAll() {
        return pending.values();
    }
}
