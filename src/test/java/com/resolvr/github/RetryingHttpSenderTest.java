package com.resolvr.github;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryingHttpSenderTest {

    private HttpServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void succeedsImmediately_onFirst200() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "ok");
        });
        server.start();

        HttpResponse<String> resp = RetryingHttpSender.send(http, HttpRequest.newBuilder(URI.create(url("/x"))).GET().build());

        assertEquals(200, resp.statusCode());
        assertEquals(1, calls.get());
    }

    @Test
    void retriesOnce_after500ThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 503, "unavailable");
            } else {
                respond(exchange, 200, "ok");
            }
        });
        server.start();

        HttpResponse<String> resp = RetryingHttpSender.send(http, HttpRequest.newBuilder(URI.create(url("/x"))).GET().build());

        assertEquals(200, resp.statusCode());
        assertEquals(2, calls.get(), "should have retried exactly once after the 503");
    }

    @Test
    void persistent500_returnsLastResponseWithoutThrowing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 500, "still broken");
        });
        server.start();

        HttpResponse<String> resp = RetryingHttpSender.send(http, HttpRequest.newBuilder(URI.create(url("/x"))).GET().build());

        assertEquals(500, resp.statusCode());
        assertEquals(3, calls.get(), "should have exhausted all 3 attempts");
    }

    @Test
    void rateLimited429_withRetryAfter_waitsThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 429, "rate limited");
            } else {
                respond(exchange, 200, "ok");
            }
        });
        server.start();

        long start = System.currentTimeMillis();
        HttpResponse<String> resp = RetryingHttpSender.send(http, HttpRequest.newBuilder(URI.create(url("/x"))).GET().build());
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(200, resp.statusCode());
        assertEquals(2, calls.get());
        assertTrue(elapsed >= 1000, "should have honored the 1s Retry-After before retrying");
    }

    @Test
    void primaryRateLimit_farFromReset_abortsImmediatelyRatherThanBlocking() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", exchange -> {
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", "0");
            exchange.getResponseHeaders().add("X-RateLimit-Reset",
                    String.valueOf(Instant.now().getEpochSecond() + 3600));
            respond(exchange, 403, "rate limited");
        });
        server.start();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> RetryingHttpSender.send(http, HttpRequest.newBuilder(URI.create(url("/x"))).GET().build()));
        assertTrue(ex.getMessage().contains("rate limit"));
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
