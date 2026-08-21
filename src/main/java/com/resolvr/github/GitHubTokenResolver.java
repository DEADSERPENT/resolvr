package com.resolvr.github;

import io.quarkus.logging.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Falls back to `gh auth token` (the GitHub CLI's active session) when no
 * github.token is configured, so a local MCP server needs no manual PAT setup
 * if the developer already ran `gh auth login`.
 */
final class GitHubTokenResolver {

    // Shared across GitHubRestClient and GitHubGraphQLClient so the CLI is
    // shelled out to at most once per process, not once per client.
    private static final AtomicReference<String> cachedCliToken = new AtomicReference<>();

    private GitHubTokenResolver() {}

    static String resolve(String configuredToken) {
        if (configuredToken != null && !configuredToken.isBlank()
                && !"your-token-here".equals(configuredToken)) {
            return configuredToken;
        }

        String cached = cachedCliToken.get();
        if (cached != null) {
            return cached;
        }

        String fromCli = tokenFromGhCli();
        if (fromCli != null) {
            Log.info("github.token not set — using `gh auth token` from the GitHub CLI session");
            cachedCliToken.set(fromCli);
            return fromCli;
        }

        throw new IllegalStateException(
                "No GitHub token available: set GITHUB_TOKEN (or github.token), or run `gh auth login`.");
    }

    private static String tokenFromGhCli() {
        try {
            Process process = new ProcessBuilder("gh", "auth", "token").start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || output == null || output.isBlank()) {
                return null;
            }
            return output.trim();
        } catch (Exception e) {
            Log.debugf("gh CLI token lookup failed: %s", e.getMessage());
            return null;
        }
    }
}
