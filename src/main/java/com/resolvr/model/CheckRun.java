package com.resolvr.model;

/** One GitHub Checks API run. status is queued/in_progress/completed; conclusion (only set once completed) is
 * success/failure/neutral/cancelled/timed_out/action_required/skipped/stale, or null while not yet completed. */
public record CheckRun(
        String name,
        String status,
        String conclusion,
        String htmlUrl
) {
}
