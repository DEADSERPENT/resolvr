package com.resolvr.github;

import io.quarkus.logging.Log;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

/**
 * Shared retry/backoff/rate-limit handling for GitHub REST + GraphQL calls.
 * Both clients funnel their HTTP send through here so transient failures and
 * GitHub's rate limiting are handled once instead of duplicated per client.
 */
final class RetryingHttpSender {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 400L;
    private static final long MAX_RATE_LIMIT_WAIT_MS = 30_000L;

    private RetryingHttpSender() {
    }

    static HttpResponse<String> send(HttpClient http, HttpRequest req) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) throw e;
                long backoffMs = BASE_BACKOFF_MS * (1L << (attempt - 1));
                Log.warnf("GitHub API call failed (%s) — retrying in %dms (%d/%d)",
                        e.getMessage(), backoffMs, attempt, MAX_ATTEMPTS);
                Thread.sleep(backoffMs);
                continue;
            }

            if (isRateLimited(resp)) {
                long waitMs = rateLimitWaitMillis(resp);
                if (waitMs > MAX_RATE_LIMIT_WAIT_MS) {
                    throw new RuntimeException("GitHub API rate limit exceeded — resets in "
                            + (waitMs / 1000) + "s, aborting rather than blocking");
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new RuntimeException("GitHub API still rate limited after " + MAX_ATTEMPTS + " attempts");
                }
                Log.warnf("GitHub API rate limited (HTTP %d) — waiting %dms before retry (%d/%d)",
                        resp.statusCode(), waitMs, attempt, MAX_ATTEMPTS);
                Thread.sleep(waitMs);
                continue;
            }

            if (resp.statusCode() >= 500 && attempt < MAX_ATTEMPTS) {
                long backoffMs = BASE_BACKOFF_MS * (1L << (attempt - 1));
                Log.warnf("GitHub API returned HTTP %d — retrying in %dms (%d/%d)",
                        resp.statusCode(), backoffMs, attempt, MAX_ATTEMPTS);
                Thread.sleep(backoffMs);
                continue;
            }

            return resp;
        }
        throw new IllegalStateException("unreachable — loop always returns or throws");
    }

    private static boolean isRateLimited(HttpResponse<String> resp) {
        if (resp.statusCode() == 429) return true;
        if (resp.statusCode() == 403) {
            return resp.headers().firstValue("X-RateLimit-Remaining").map("0"::equals).orElse(false);
        }
        return false;
    }

    private static long rateLimitWaitMillis(HttpResponse<String> resp) {
        var retryAfter = resp.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                return Long.parseLong(retryAfter.get()) * 1000L;
            } catch (NumberFormatException ignored) {
                // fall through to reset-time based calculation
            }
        }
        var reset = resp.headers().firstValue("X-RateLimit-Reset");
        if (reset.isPresent()) {
            try {
                long waitSec = Long.parseLong(reset.get()) - Instant.now().getEpochSecond();
                return Math.max(0, waitSec) * 1000L;
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 5_000L;
    }
}
