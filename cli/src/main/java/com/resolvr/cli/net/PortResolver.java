package com.resolvr.cli.net;

import java.util.Map;

/** Resolves the port Resolvr's HTTP/MCP endpoint is expected on, matching the server's own
 * {@code quarkus.http.port=${PORT:8080}} convention (application.properties) — reads the
 * same PORT env var so the CLI and server never disagree about where to look. */
public final class PortResolver {

    public static final int DEFAULT_PORT = 8080;

    private PortResolver() {
    }

    public static int resolve(Map<String, String> env) {
        String value = env.get("PORT");
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    public static int resolveFromEnvironment() {
        return resolve(System.getenv());
    }
}
