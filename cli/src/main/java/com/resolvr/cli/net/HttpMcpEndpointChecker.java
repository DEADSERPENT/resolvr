package com.resolvr.cli.net;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Real implementation. An SSE endpoint holds its HTTP response open indefinitely, so a plain
 * synchronous GET would hang until the timeout regardless of whether the endpoint is actually
 * reachable. Using the async API with {@code BodyHandlers.ofInputStream()} instead: the
 * returned future completes as soon as the response headers arrive (status + content-type),
 * which is all this check needs — the body is then never read, just closed.
 */
public final class HttpMcpEndpointChecker implements McpEndpointChecker {

    private final HttpClient client;

    public HttpMcpEndpointChecker() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public McpEndpointStatus check(URI sseUrl, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(sseUrl)
                .GET()
                .header("Accept", "text/event-stream")
                .build();
        try {
            HttpResponse<InputStream> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (status == 401 || status == 403) {
                    return McpEndpointStatus.REACHABLE_AUTH_REQUIRED;
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (status == 200 && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")) {
                    return McpEndpointStatus.REACHABLE;
                }
                return McpEndpointStatus.UNREACHABLE;
            }
        } catch (TimeoutException | ExecutionException e) {
            return McpEndpointStatus.UNREACHABLE;
        } catch (java.io.IOException e) {
            return McpEndpointStatus.UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpEndpointStatus.UNREACHABLE;
        }
    }
}
