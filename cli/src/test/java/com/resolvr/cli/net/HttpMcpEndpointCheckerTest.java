package com.resolvr.cli.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class HttpMcpEndpointCheckerTest {

    private HttpServer server;
    private final HttpMcpEndpointChecker checker = new HttpMcpEndpointChecker();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sseResponse_isReachable() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp/sse", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            // Headers-only, then hold the connection open like a real SSE stream would —
            // the checker must not need to read the body to report REACHABLE.
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
        });
        server.start();

        McpEndpointStatus status = checker.check(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp/sse"), Duration.ofSeconds(3));
        assertEquals(McpEndpointStatus.REACHABLE, status);
    }

    @Test
    void unauthorizedResponse_isReachableAuthRequired() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp/sse", exchange -> {
            byte[] body = "{\"error\":\"missing or invalid API key\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        McpEndpointStatus status = checker.check(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp/sse"), Duration.ofSeconds(3));
        assertEquals(McpEndpointStatus.REACHABLE_AUTH_REQUIRED, status);
    }

    @Test
    void wrongContentType_isUnreachable() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp/sse", exchange -> {
            byte[] body = "not sse".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        McpEndpointStatus status = checker.check(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp/sse"), Duration.ofSeconds(3));
        assertEquals(McpEndpointStatus.UNREACHABLE, status);
    }

    @Test
    void nothingListening_isUnreachable() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        McpEndpointStatus status = checker.check(URI.create("http://127.0.0.1:" + port + "/mcp/sse"), Duration.ofSeconds(2));
        assertEquals(McpEndpointStatus.UNREACHABLE, status);
    }
}
