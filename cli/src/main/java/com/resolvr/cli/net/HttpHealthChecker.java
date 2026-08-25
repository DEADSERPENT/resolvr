package com.resolvr.cli.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Real implementation: a plain GET via java.net.http.HttpClient — no curl, no shell-out. */
public final class HttpHealthChecker implements HealthChecker {

    private final HttpClient client;

    public HttpHealthChecker() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public HealthStatus check(URI healthUrl, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(healthUrl)
                .GET()
                .timeout(timeout)
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY;
        } catch (IOException e) {
            return HealthStatus.UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HealthStatus.UNREACHABLE;
        }
    }
}
