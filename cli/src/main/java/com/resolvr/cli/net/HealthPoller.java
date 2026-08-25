package com.resolvr.cli.net;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/** Retry loop around a {@link HealthChecker} — kept separate from the checker itself so the
 * checker stays a trivial single-shot interface and this loop's timing logic is independently
 * testable with a fake checker (no real sleeping required beyond a short, deterministic poll
 * interval the test controls). */
public final class HealthPoller {

    private HealthPoller() {
    }

    public static HealthStatus pollUntilHealthy(HealthChecker checker, URI url, Duration perCheckTimeout,
                                                 Duration overallTimeout, Duration pollInterval)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(overallTimeout);
        HealthStatus last;
        while (true) {
            last = checker.check(url, perCheckTimeout);
            if (last == HealthStatus.HEALTHY) {
                return last;
            }
            if (Instant.now().isAfter(deadline)) {
                return last;
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }
}
