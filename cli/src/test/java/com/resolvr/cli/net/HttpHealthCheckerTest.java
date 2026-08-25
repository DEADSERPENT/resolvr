package com.resolvr.cli.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises HttpHealthChecker against a real local HTTP server (JDK's built-in
 * com.sun.net.httpserver.HttpServer, same pattern the server module's own GitHubRestClientTest
 * uses) — a genuine HTTP round trip, but not the real Resolvr server. */
class HttpHealthCheckerTest {

    private HttpServer server;
    private final HttpHealthChecker checker = new HttpHealthChecker();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private int startServerReturning(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/q/health", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    void returns200_isHealthy() throws Exception {
        int port = startServerReturning(200, "{\"status\":\"UP\"}");
        HealthStatus status = checker.check(URI.create("http://127.0.0.1:" + port + "/q/health"), Duration.ofSeconds(3));
        assertEquals(HealthStatus.HEALTHY, status);
    }

    @Test
    void returns503_isUnhealthy() throws Exception {
        int port = startServerReturning(503, "{\"status\":\"DOWN\"}");
        HealthStatus status = checker.check(URI.create("http://127.0.0.1:" + port + "/q/health"), Duration.ofSeconds(3));
        assertEquals(HealthStatus.UNHEALTHY, status);
    }

    @Test
    void nothingListening_isUnreachable() throws Exception {
        // Bind an ephemeral port, close it immediately, and use that now-free port — avoids
        // relying on any platform-specific behavior for a hardcoded low/reserved port number.
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        HealthStatus status = checker.check(URI.create("http://127.0.0.1:" + port + "/q/health"), Duration.ofSeconds(2));
        assertEquals(HealthStatus.UNREACHABLE, status);
    }
}
