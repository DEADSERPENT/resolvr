package com.resolvr.cli.net;

import java.net.URI;
import java.time.Duration;

/** Abstraction over a single health-check HTTP call, so command classes and the poll loop
 * can be unit-tested against a fake without ever making a real network call. */
public interface HealthChecker {
    HealthStatus check(URI healthUrl, Duration timeout);
}
