package com.resolvr.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Intentional, planted failure for the Phase 5 CI feedback-loop golden-path test
 * (get_ci_status / get_ci_failure_logs against a real failing GitHub Actions run).
 * Not part of product test coverage — safe to delete once the golden-path run is done.
 */
class GoldenPathCiFailureTest {

    @Test
    void intentionalFailure_overallStatusForAFailingCheck() {
        List<CheckRun> checks = List.of(
                new CheckRun(1L, "build", "completed", "failure", "https://example.com/1"));

        String overallStatus = CiConclusions.overallStatus(checks);

        // Deliberately wrong expectation: overallStatus() actually returns "FAILING" here.
        // This mismatch is the planted golden-path CI failure.
        assertEquals("PASSING", overallStatus);
    }
}
