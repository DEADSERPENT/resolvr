package com.resolvr.orchestrator;

import com.resolvr.model.PRReviewEvent;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@ApplicationScoped
public class PRReviewOrchestrator {

    // Bounded deque — keeps last 500 events to prevent unbounded growth
    private final ConcurrentLinkedDeque<PRReviewEvent> queue = new ConcurrentLinkedDeque<>();
    private static final int MAX_QUEUE_SIZE = 500;

    public void enqueue(String owner, String repo, int prNumber, String action) {
        PRReviewEvent event = new PRReviewEvent(owner, repo, prNumber, action, Instant.now());
        queue.addLast(event);
        if (queue.size() > MAX_QUEUE_SIZE) {
            queue.pollFirst(); // drop oldest
        }
        Log.infof("[QUEUE] +1 event → %s/%s#%d (%s) | queue size=%d",
                owner, repo, prNumber, action, queue.size());
    }

    /** Drain all pending events — call from MCP tool to check for new reviews. */
    public List<PRReviewEvent> drainAll() {
        List<PRReviewEvent> drained = new ArrayList<>();
        PRReviewEvent event;
        while ((event = queue.pollFirst()) != null) {
            drained.add(event);
        }
        return drained;
    }

    /** Non-destructive peek — returns count without consuming. */
    public int pendingCount() {
        return queue.size();
    }

    public boolean hasPending() {
        return !queue.isEmpty();
    }
}
