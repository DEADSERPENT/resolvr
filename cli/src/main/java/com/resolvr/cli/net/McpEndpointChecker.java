package com.resolvr.cli.net;

import java.net.URI;
import java.time.Duration;

/** Abstraction over probing the MCP/SSE endpoint, mirroring {@link HealthChecker} so command
 * classes can be tested against a fake without a real network call. */
public interface McpEndpointChecker {
    McpEndpointStatus check(URI sseUrl, Duration timeout);
}
