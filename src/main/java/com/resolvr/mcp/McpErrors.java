package com.resolvr.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Every MCP tool catch block needs to turn a Java exception message into a JSON error
 * response. Hand-concatenating {"error":"...spliced message..."} breaks the moment the
 * message itself contains a quote, backslash, or newline — which GitHub API error bodies
 * and GraphQL error arrays routinely do — producing invalid JSON exactly when the agent
 * most needs a parseable error. Route every tool's error path through here instead so
 * escaping is never re-implemented (or missed) per call site.
 */
final class McpErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpErrors() {
    }

    static String error(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message == null ? "unknown error" : message));
        } catch (Exception e) {
            // Map.of("error", String) serialization cannot itself fail — this is unreachable
            // in practice, but a hardcoded literal (not the exception message) keeps it safe
            // if it ever did.
            return "{\"error\":\"internal error serializing error response\"}";
        }
    }
}
