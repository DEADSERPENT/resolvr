package com.resolvr.cli.net;

/** Outcome of probing the /mcp/sse endpoint. */
public enum McpEndpointStatus {
    /** Got a streaming text/event-stream response — an IDE could connect right now. */
    REACHABLE,
    /** Got a 401/403 — the endpoint exists and is guarded by RESOLVR_API_KEY; the IDE will
     * need to be configured with an Authorization header, but the server side is fine. */
    REACHABLE_AUTH_REQUIRED,
    /** No usable response — connection refused, timed out, or an unexpected status/content-type. */
    UNREACHABLE
}
