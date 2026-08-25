package com.resolvr.cli.testutil;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A tiny standalone HTTP server, spawned as a real OS subprocess by ServerProcessManagerTest
 * (via the same `java` executable running the test JVM) so process start/stop/PID-tracking
 * behavior is exercised against a genuine child process on every OS — without needing the
 * actual Quarkus server built. Args: [port]. Serves /q/health returning 200.
 */
public final class FakeServerMain {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/q/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        // Keep running until killed by the test (destroy()/destroyForcibly()).
        Thread.sleep(Long.MAX_VALUE);
    }

    private FakeServerMain() {
    }
}
