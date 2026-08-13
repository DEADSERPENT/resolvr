package com.resolvr.github;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit5 tests against a real local HTTP server standing in for the
 * GitHub REST API. Exercises the base64 encode/decode round-trip and the
 * optimistic-concurrency sha lookup that gates every commit this bridge makes.
 */
class GitHubRestClientTest {

    private HttpServer server;
    private GitHubRestClient client;

    /** method+path -> canned (status, body) response */
    private final Map<String, int[]> statusByRoute = new ConcurrentHashMap<>();
    private final Map<String, String> bodyByRoute = new ConcurrentHashMap<>();
    /** captures the last request body seen for a given method+path, for assertions */
    private final Map<String, String> capturedRequestBodies = new ConcurrentHashMap<>();

    private void stub(String method, String path, int status, String responseBody) {
        String key = method + " " + path;
        statusByRoute.put(key, new int[]{status});
        bodyByRoute.put(key, responseBody);
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedRequestBodies.put(key, reqBody);

            int status = statusByRoute.containsKey(key) ? statusByRoute.get(key)[0] : 404;
            String respBody = bodyByRoute.getOrDefault(key, "{\"message\":\"not stubbed: " + key + "\"}");
            respond(exchange, status, respBody);
        });
        server.start();

        client = new GitHubRestClient();
        client.apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
        client.githubToken = "test-token";
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void getFileContent_decodesBase64() throws Exception {
        String encoded = Base64.getEncoder().encodeToString("hello world".getBytes(StandardCharsets.UTF_8));
        startServer();
        stub("GET", "/repos/octocat/hello-world/contents/Foo.java", 200,
                "{\"content\":\"" + encoded + "\",\"sha\":\"abc123\"}");

        String content = client.getFileContent("octocat", "hello-world", "main", "Foo.java");

        assertEquals("hello world", content);
    }

    @Test
    void commitFileChange_existingFile_includesShaForOptimisticConcurrency() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/contents/Foo.java", 200, "{\"sha\":\"abc123\"}");
        stub("PUT", "/repos/octocat/hello-world/contents/Foo.java", 200,
                "{\"commit\":{\"sha\":\"newsha456\"}}");

        String commitSha = client.commitFileChange("octocat", "hello-world", "main",
                "Foo.java", "new content", "fix: address review comment");

        assertEquals("newsha456", commitSha);
        String putBody = capturedRequestBodies.get("PUT /repos/octocat/hello-world/contents/Foo.java");
        assertTrue(putBody.contains("\"sha\":\"abc123\""), "must send the current sha for optimistic concurrency");
        assertTrue(putBody.contains(Base64.getEncoder().encodeToString("new content".getBytes(StandardCharsets.UTF_8))));
        assertTrue(putBody.contains("\"branch\":\"main\""));
    }

    @Test
    void commitFileChange_newFile_omitsSha() throws Exception {
        startServer();
        // sha lookup 404s — file does not exist yet on this branch
        stub("GET", "/repos/octocat/hello-world/contents/NewFile.java", 404, "{\"message\":\"Not Found\"}");
        stub("PUT", "/repos/octocat/hello-world/contents/NewFile.java", 201,
                "{\"commit\":{\"sha\":\"createdsha\"}}");

        String commitSha = client.commitFileChange("octocat", "hello-world", "main",
                "NewFile.java", "brand new content", "feat: add NewFile.java");

        assertEquals("createdsha", commitSha);
        String putBody = capturedRequestBodies.get("PUT /repos/octocat/hello-world/contents/NewFile.java");
        assertFalse(putBody.contains("\"sha\""), "must not send a sha when creating a new file");
    }

    @Test
    void getPRHeadBranch_returnsRef() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/pulls/5", 200, "{\"head\":{\"ref\":\"feature/x\"}}");

        assertEquals("feature/x", client.getPRHeadBranch("octocat", "hello-world", 5));
    }

    @Test
    void getFileContent_persistentServerError_throws() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/contents/Broken.java", 500, "{\"message\":\"boom\"}");

        assertThrows(RuntimeException.class,
                () -> client.getFileContent("octocat", "hello-world", "main", "Broken.java"));
    }
}
