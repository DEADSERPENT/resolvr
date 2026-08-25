package com.resolvr.github;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
        client.githubToken = Optional.of("test-token");
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
    void getPRHeadBranch_returnsRef() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/pulls/5", 200, "{\"head\":{\"ref\":\"feature/x\"}}");

        assertEquals("feature/x", client.getPRHeadBranch("octocat", "hello-world", 5));
    }

    // ─── listOpenPullRequests (workspace → PR discovery) ────────────────────────

    @Test
    void listOpenPullRequests_oneMatch_returnsSummary() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/pulls", 200, """
                [{"number":42,"title":"Fix auth","state":"open","html_url":"https://github.com/octocat/hello-world/pull/42",
                  "base":{"ref":"main"},"head":{"ref":"feature/auth","sha":"abc123"}}]
                """);

        var results = client.listOpenPullRequests("octocat", "hello-world", "feature/auth");

        assertEquals(1, results.size());
        var pr = results.get(0);
        assertEquals(42, pr.number());
        assertEquals("Fix auth", pr.title());
        assertEquals("main", pr.baseBranch());
        assertEquals("feature/auth", pr.headBranch());
        assertEquals("abc123", pr.headSha());
        assertEquals("open", pr.state());
    }

    @Test
    void listOpenPullRequests_noMatch_returnsEmptyList() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/pulls", 200, "[]");

        assertTrue(client.listOpenPullRequests("octocat", "hello-world", "feature/none").isEmpty());
    }

    @Test
    void listOpenPullRequests_multipleMatches_returnsAll() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/pulls", 200, """
                [{"number":42,"title":"A","state":"open","base":{"ref":"main"},"head":{"ref":"feature/x","sha":"a1"}},
                 {"number":43,"title":"B","state":"open","base":{"ref":"develop"},"head":{"ref":"feature/x","sha":"a1"}}]
                """);

        assertEquals(2, client.listOpenPullRequests("octocat", "hello-world", "feature/x").size());
    }

    @Test
    void listOpenPullRequests_apiFailure_throws() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/pulls", 503, "{\"message\":\"unavailable\"}");

        assertThrows(RuntimeException.class,
                () -> client.listOpenPullRequests("octocat", "hello-world", "feature/x"));
    }

    @Test
    void listOpenPullRequests_nonArrayResponse_throwsInsteadOfMisparsing() throws Exception {
        startServer();
        // e.g. a redirect body that wasn't followed, or any other unexpected object shape
        stub("GET", "/repos/octocat/hello-world/pulls", 200, "{\"message\":\"Moved Permanently\"}");

        assertThrows(GitHubApiException.class,
                () -> client.listOpenPullRequests("octocat", "hello-world", "feature/x"));
    }

    @Test
    void listOpenPullRequests_redirectedRepo_followsRedirectInsteadOfMisparsingBody() throws Exception {
        // Reproduces a real bug found against a live renamed GitHub repo: GitHub 301s
        // repos/{owner}/{old-name}/... to repositories/{id}/...; without following
        // redirects, the 3xx JSON body was misread as if it were the PR array itself.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/octocat/old-name/pulls", exchange -> {
            int port = exchange.getLocalAddress().getPort();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/repositories/123/pulls");
            byte[] bytes = "{\"message\":\"Moved Permanently\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(301, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.createContext("/repositories/123/pulls", exchange -> respond(exchange, 200, """
                [{"number":42,"title":"Fix","state":"open","base":{"ref":"main"},"head":{"ref":"feature/x","sha":"sha1"}}]
                """));
        server.start();
        client = new GitHubRestClient();
        client.apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
        client.githubToken = Optional.of("test-token");

        var results = client.listOpenPullRequests("octocat", "old-name", "feature/x");

        assertEquals(1, results.size(), "must follow the redirect rather than misparsing the 3xx body as the PR list");
        assertEquals(42, results.get(0).number());
    }

    // ─── getPullRequest (PR Context Engine) ──────────────────────────────────────

    @Test
    void getPullRequest_returnsFullMetadata() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/pulls/42", 200, """
                {"number":42,"title":"Fix auth","body":"Handles expired tokens","state":"open",
                 "user":{"login":"octocat"},"base":{"ref":"main"},"head":{"ref":"feature/auth","sha":"abc123"},
                 "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z",
                 "html_url":"https://github.com/octocat/hello-world/pull/42"}
                """);

        var pr = client.getPullRequest("octocat", "hello-world", 42);

        assertEquals(42, pr.number());
        assertEquals("Fix auth", pr.title());
        assertEquals("Handles expired tokens", pr.body());
        assertEquals("open", pr.state());
        assertEquals("octocat", pr.author());
        assertEquals("main", pr.baseBranch());
        assertEquals("feature/auth", pr.headBranch());
        assertEquals("abc123", pr.headSha());
        assertEquals("2026-01-01T00:00:00Z", pr.createdAt());
        assertEquals("https://github.com/octocat/hello-world/pull/42", pr.htmlUrl());
    }

    // ─── listChangedFiles ─────────────────────────────────────────────────────────

    @Test
    void listChangedFiles_returnsFiles() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/pulls/7/files", 200, """
                [{"filename":"Foo.java","additions":10,"deletions":2,"status":"modified","sha":"filesha1"}]
                """);

        var files = client.listChangedFiles("octocat", "hello-world", 7);

        assertEquals(1, files.size());
        assertEquals("Foo.java", files.get(0).path());
        assertEquals(10, files.get(0).additions());
        assertEquals(2, files.get(0).deletions());
        assertEquals("modified", files.get(0).status());
        assertEquals("filesha1", files.get(0).sha());
    }

    @Test
    void listChangedFiles_paginatesPastFirstPage() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/octocat/hello-world/pulls/7/files", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String body = (query != null && query.endsWith("&page=1")) ? repeatedFilesJson(100) : repeatedFilesJson(1);
            respond(exchange, 200, body);
        });
        server.start();
        client = new GitHubRestClient();
        client.apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
        client.githubToken = Optional.of("test-token");

        var files = client.listChangedFiles("octocat", "hello-world", 7);

        assertEquals(101, files.size(), "must page past the 100-item boundary");
    }

    private static String repeatedFilesJson(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"filename\":\"File").append(i).append(".java\",\"additions\":1,\"deletions\":0,")
                    .append("\"status\":\"modified\",\"sha\":\"sha").append(i).append("\"}");
        }
        return sb.append("]").toString();
    }

    // ─── listCommits ──────────────────────────────────────────────────────────────

    @Test
    void listCommits_returnsCommits() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/pulls/7/commits", 200, """
                [{"sha":"c1","commit":{"message":"fix: null check","author":{"name":"Ada","date":"2026-01-01T00:00:00Z"}}}]
                """);

        var commits = client.listCommits("octocat", "hello-world", 7);

        assertEquals(1, commits.size());
        assertEquals("c1", commits.get(0).sha());
        assertEquals("fix: null check", commits.get(0).message());
        assertEquals("Ada", commits.get(0).author());
        assertEquals("2026-01-01T00:00:00Z", commits.get(0).timestamp());
    }

    // ─── listIssueComments ────────────────────────────────────────────────────────

    @Test
    void listIssueComments_returnsComments() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/issues/7/comments", 200, """
                [{"user":{"login":"reviewer1"},"body":"LGTM overall","created_at":"2026-01-01T00:00:00Z"}]
                """);

        var comments = client.listIssueComments("octocat", "hello-world", 7);

        assertEquals(1, comments.size());
        assertEquals("reviewer1", comments.get(0).author());
        assertEquals("LGTM overall", comments.get(0).body());
    }

    @Test
    void listIssueComments_emptyList_returnsEmpty() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/issues/7/comments", 200, "[]");

        assertTrue(client.listIssueComments("octocat", "hello-world", 7).isEmpty());
    }

    // ─── getDiff ──────────────────────────────────────────────────────────────────

    @Test
    void getDiff_returnsRawDiffText() throws Exception {
        startServer();
        String diffText = "diff --git a/Foo.java b/Foo.java\n+added line\n-removed line\n";
        stub("GET", "/repos/octocat/hello-world/pulls/7", 200, diffText);

        assertEquals(diffText, client.getDiff("octocat", "hello-world", 7));
    }

    @Test
    void getDiff_apiFailure_throws() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/pulls/7", 404, "{\"message\":\"Not Found\"}");

        assertThrows(GitHubApiException.class, () -> client.getDiff("octocat", "hello-world", 7));
    }

    // ─── listCheckRuns (CI status) ────────────────────────────────────────────────

    @Test
    void listCheckRuns_returnsRuns() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/commits/abc123/check-runs", 200, """
                {"total_count":2,"check_runs":[
                  {"id":111,"name":"unit-tests","status":"completed","conclusion":"success","html_url":"https://x/1"},
                  {"id":222,"name":"build","status":"completed","conclusion":"failure","html_url":"https://x/2"}
                ]}
                """);

        var runs = client.listCheckRuns("octocat", "hello-world", "abc123");

        assertEquals(2, runs.size());
        assertEquals(111L, runs.get(0).id());
        assertEquals("unit-tests", runs.get(0).name());
        assertEquals("success", runs.get(0).conclusion());
        assertEquals(222L, runs.get(1).id());
        assertEquals("failure", runs.get(1).conclusion());
    }

    @Test
    void listCheckRuns_paginatesPastFirstPage() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/octocat/hello-world/commits/abc123/check-runs", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String body = (query != null && query.endsWith("&page=1"))
                    ? "{\"total_count\":101,\"check_runs\":" + repeatedCheckRunsJson(100) + "}"
                    : "{\"total_count\":101,\"check_runs\":" + repeatedCheckRunsJson(1) + "}";
            respond(exchange, 200, body);
        });
        server.start();
        client = new GitHubRestClient();
        client.apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
        client.githubToken = Optional.of("test-token");

        var runs = client.listCheckRuns("octocat", "hello-world", "abc123");

        assertEquals(101, runs.size(), "must page past the 100-item boundary");
    }

    private static String repeatedCheckRunsJson(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"check").append(i).append("\",\"status\":\"completed\",\"conclusion\":\"success\"}");
        }
        return sb.append("]").toString();
    }

    // ─── getCheckRunLogText (CI failure logs) ──────────────────────────────────

    @Test
    void getCheckRunLogText_followsRedirectToPlainTextLog() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/octocat/hello-world/actions/jobs/999/logs", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/blob-storage/log.txt");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/blob-storage/log.txt", exchange -> {
            byte[] body = "line1\nline2\nERROR: build failed\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        client = new GitHubRestClient();
        client.apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
        client.githubToken = Optional.of("test-token");

        Optional<String> log = client.getCheckRunLogText("octocat", "hello-world", 999);

        assertTrue(log.isPresent());
        assertTrue(log.get().contains("ERROR: build failed"));
    }

    @Test
    void getCheckRunLogText_crossAuthorityRedirect_authorizationHeaderNotForwarded() throws Exception {
        // GitHub's real /actions/jobs/{id}/logs endpoint redirects to blob storage on a
        // completely different host than api.github.com. The single-server test above only
        // proves the redirect is followed — it can't prove the Authorization header (this
        // client's GitHub token) isn't forwarded to that third-party host, because its
        // redirect target lives on the same server/port (same authority) as the source.
        // This test uses two separate local servers on different ports — a genuinely
        // different authority — and asserts the token never reaches the second one.
        AtomicReference<String> authHeaderSeenByTarget = new AtomicReference<>("NOT_CALLED");

        HttpServer targetServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        targetServer.createContext("/blob-storage/log.txt", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authHeaderSeenByTarget.set(auth == null ? "ABSENT" : "PRESENT:" + auth);
            byte[] body = "line1\nERROR: build failed\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        targetServer.start();

        try {
            int targetPort = targetServer.getAddress().getPort();

            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/repos/octocat/hello-world/actions/jobs/999/logs", exchange -> {
                exchange.getResponseHeaders().add("Location",
                        "http://127.0.0.1:" + targetPort + "/blob-storage/log.txt");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.start();
            client = new GitHubRestClient();
            client.apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
            client.githubToken = Optional.of("test-token");

            Optional<String> log = client.getCheckRunLogText("octocat", "hello-world", 999);

            assertTrue(log.isPresent());
            assertTrue(log.get().contains("ERROR: build failed"));
            assertEquals("ABSENT", authHeaderSeenByTarget.get(),
                    "the cross-authority redirect target must never see this client's GitHub token");
        } finally {
            targetServer.stop(0);
        }
    }

    @Test
    void getCheckRunLogText_notFound_returnsEmpty() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/actions/jobs/999/logs", 404, "{\"message\":\"Not Found\"}");

        Optional<String> log = client.getCheckRunLogText("octocat", "hello-world", 999);

        assertTrue(log.isEmpty(), "a non-Actions check's log lookup should be absence, not an exception");
    }

    @Test
    void getCheckRunLogText_serverError_throws() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/actions/jobs/999/logs", 500, "{\"message\":\"boom\"}");

        assertThrows(GitHubApiException.class,
                () -> client.getCheckRunLogText("octocat", "hello-world", 999));
    }

    @Test
    void getFileContent_persistentServerError_throws() throws Exception {
        startServer();
        stub("GET", "/repos/octocat/hello-world/contents/Broken.java", 500, "{\"message\":\"boom\"}");

        assertThrows(RuntimeException.class,
                () -> client.getFileContent("octocat", "hello-world", "main", "Broken.java"));
    }

}
