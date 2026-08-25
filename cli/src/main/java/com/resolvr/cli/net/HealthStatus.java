package com.resolvr.cli.net;

/** Outcome of a single health-check attempt against Resolvr's /q/health endpoint. */
public enum HealthStatus {
    /** Got an HTTP 200 — the server is up and reports itself healthy. */
    HEALTHY,
    /** Got a response, but not a healthy one (e.g. 503 from smallrye-health while a check is down). */
    UNHEALTHY,
    /** No response at all — connection refused, timed out, or the server isn't listening yet. */
    UNREACHABLE
}
