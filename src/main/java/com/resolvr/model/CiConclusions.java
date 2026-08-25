package com.resolvr.model;

import java.util.List;
import java.util.Set;

/**
 * The GitHub Checks API's `conclusion` values, bucketed into failing/passing, plus the
 * PASSING/FAILING/PENDING/UNKNOWN roll-up derived from them. PRContextService's CI summary
 * and CiStatusService's status/failure-log lookup both go through {@link #overallStatus}
 * so they can't silently disagree on what counts as a failure if GitHub adds a new
 * conclusion value in the future.
 */
public final class CiConclusions {

    public static final Set<String> FAILING = Set.of(
            "failure", "timed_out", "cancelled", "action_required", "stale");
    public static final Set<String> PASSING = Set.of(
            "success", "neutral", "skipped");

    /** PASSING, FAILING, PENDING, or UNKNOWN (empty check list, or a shape this can't classify). */
    public static String overallStatus(List<CheckRun> checks) {
        if (checks.isEmpty()) {
            return "UNKNOWN";
        }
        if (checks.stream().anyMatch(c -> c.conclusion() != null && FAILING.contains(c.conclusion()))) {
            return "FAILING";
        }
        if (checks.stream().anyMatch(c -> !"completed".equals(c.status()))) {
            return "PENDING";
        }
        if (checks.stream().allMatch(c -> c.conclusion() != null && PASSING.contains(c.conclusion()))) {
            return "PASSING";
        }
        return "UNKNOWN";
    }

    private CiConclusions() {
    }
}
