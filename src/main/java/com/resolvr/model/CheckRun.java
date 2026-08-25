package com.resolvr.model;

/** One GitHub Checks API run. status is queued/in_progress/completed; conclusion (only set once completed) is
 * success/failure/neutral/cancelled/timed_out/action_required/skipped/stale, or null while not yet completed.
 * id is the Check Run id — for checks created by the native GitHub Actions app, this is the same id the
 * Actions Jobs API expects (`/actions/jobs/{id}/logs`), which is what makes {@code getCheckRunLogText}
 * possible without a separate workflow-runs/jobs lookup. Checks created by third-party apps won't have a
 * matching Actions job, so that lookup 404s for them — a real possibility, not a hypothetical. */
public record CheckRun(
        long id,
        String name,
        String status,
        String conclusion,
        String htmlUrl
) {
}
