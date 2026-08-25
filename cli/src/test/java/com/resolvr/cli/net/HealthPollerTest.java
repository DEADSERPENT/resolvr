package com.resolvr.cli.net;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

class HealthPollerTest {

    private static HealthChecker sequence(HealthStatus... statuses) {
        Deque<HealthStatus> queue = new ArrayDeque<>();
        for (HealthStatus s : statuses) {
            queue.add(s);
        }
        return (url, timeout) -> queue.size() > 1 ? queue.poll() : queue.peek();
    }

    @Test
    void returnsImmediately_whenFirstCheckIsHealthy() throws Exception {
        HealthStatus result = HealthPoller.pollUntilHealthy(sequence(HealthStatus.HEALTHY), URI.create("http://x"),
                Duration.ofMillis(100), Duration.ofSeconds(5), Duration.ofMillis(10));
        assertEquals(HealthStatus.HEALTHY, result);
    }

    @Test
    void retriesUntilHealthy() throws Exception {
        HealthStatus result = HealthPoller.pollUntilHealthy(
                sequence(HealthStatus.UNREACHABLE, HealthStatus.UNREACHABLE, HealthStatus.HEALTHY),
                URI.create("http://x"), Duration.ofMillis(100), Duration.ofSeconds(5), Duration.ofMillis(10));
        assertEquals(HealthStatus.HEALTHY, result);
    }

    @Test
    void givesUpAfterOverallTimeout_returningLastStatus() throws Exception {
        HealthChecker alwaysUnreachable = (url, timeout) -> HealthStatus.UNREACHABLE;
        long start = System.currentTimeMillis();
        HealthStatus result = HealthPoller.pollUntilHealthy(alwaysUnreachable, URI.create("http://x"),
                Duration.ofMillis(50), Duration.ofMillis(150), Duration.ofMillis(20));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(HealthStatus.UNREACHABLE, result);
        assertTrue(elapsed < 5000, "should give up promptly rather than hang");
    }
}
