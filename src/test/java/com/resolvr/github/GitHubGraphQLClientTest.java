package com.resolvr.github;

import com.resolvr.model.ReviewThread;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit5 tests against a real local HTTP server (JDK's built-in
 * com.sun.net.httpserver.HttpServer) standing in for the GitHub GraphQL API.
 * No mocking framework needed: graphqlUrl is redirected to 127.0.0.1, and the
 * client exercises its real HttpClient + JSON parsing against fixture responses.
 */
class GitHubGraphQLClientTest {

    private HttpServer server;
    private GitHubGraphQLClient client;

    private void startServerReturning(String responseBody, int statusCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/graphql", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        client = new GitHubGraphQLClient();
        client.graphqlUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/graphql";
        client.githubToken = "test-token";
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void getUnresolvedThreads_filtersResolvedAndFallsBackToOriginalLine() throws Exception {
        String fixture = """
                {
                  "data": {
                    "repository": {
                      "pullRequest": {
                        "headRefName": "feature/foo",
                        "reviewThreads": {
                          "nodes": [
                            {
                              "id": "RT_1",
                              "isResolved": false,
                              "comments": { "nodes": [
                                { "body": "fix this", "path": "Foo.java", "line": 10, "originalLine": 10, "author": { "login": "github-copilot" } }
                              ] }
                            },
                            {
                              "id": "RT_2",
                              "isResolved": true,
                              "comments": { "nodes": [
                                { "body": "already fixed", "path": "Bar.java", "line": 5, "originalLine": 5, "author": { "login": "someone" } }
                              ] }
                            },
                            {
                              "id": "RT_3",
                              "isResolved": false,
                              "comments": { "nodes": [
                                { "body": "line moved since review", "path": "Baz.java", "line": null, "originalLine": 20, "author": { "login": "github-copilot" } }
                              ] }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
                """;
        startServerReturning(fixture, 200);

        List<ReviewThread> threads = client.getUnresolvedThreads("octocat", "hello-world", 42);

        assertEquals(2, threads.size(), "resolved thread RT_2 must be filtered out");

        ReviewThread t1 = threads.get(0);
        assertEquals("RT_1", t1.threadId());
        assertEquals("Foo.java", t1.filePath());
        assertEquals(10, t1.line());
        assertEquals("fix this", t1.commentBody());
        assertEquals("feature/foo", t1.prBranch());
        assertEquals("github-copilot", t1.author());

        ReviewThread t3 = threads.get(1);
        assertEquals("RT_3", t3.threadId());
        assertEquals(20, t3.line(), "must fall back to originalLine when line is null");
    }

    @Test
    void getUnresolvedThreads_followsCursorAcrossMultiplePages() throws Exception {
        String page1 = """
                {
                  "data": {
                    "repository": {
                      "pullRequest": {
                        "headRefName": "feature/big-pr",
                        "reviewThreads": {
                          "pageInfo": { "hasNextPage": true, "endCursor": "CURSOR_1" },
                          "nodes": [
                            {
                              "id": "RT_PAGE1",
                              "isResolved": false,
                              "comments": { "nodes": [
                                { "body": "page one comment", "path": "A.java", "line": 1, "originalLine": 1, "author": { "login": "github-copilot" } }
                              ] }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
                """;
        String page2 = """
                {
                  "data": {
                    "repository": {
                      "pullRequest": {
                        "headRefName": "feature/big-pr",
                        "reviewThreads": {
                          "pageInfo": { "hasNextPage": false, "endCursor": null },
                          "nodes": [
                            {
                              "id": "RT_PAGE2",
                              "isResolved": false,
                              "comments": { "nodes": [
                                { "body": "page two comment", "path": "B.java", "line": 2, "originalLine": 2, "author": { "login": "github-copilot" } }
                              ] }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
                """;

        AtomicInteger callCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/graphql", exchange -> {
            String responseBody = callCount.incrementAndGet() == 1 ? page1 : page2;
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        client = new GitHubGraphQLClient();
        client.graphqlUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/graphql";
        client.githubToken = "test-token";

        List<ReviewThread> threads = client.getUnresolvedThreads("octocat", "hello-world", 42);

        assertEquals(2, callCount.get(), "must have paged exactly twice");
        assertEquals(2, threads.size());
        assertEquals("RT_PAGE1", threads.get(0).threadId());
        assertEquals("RT_PAGE2", threads.get(1).threadId());
        assertEquals("feature/big-pr", threads.get(1).prBranch());
    }

    @Test
    void getUnresolvedThreads_emptyThreadList_returnsEmpty() throws Exception {
        String fixture = """
                {
                  "data": {
                    "repository": {
                      "pullRequest": {
                        "headRefName": "main",
                        "reviewThreads": { "nodes": [] }
                      }
                    }
                  }
                }
                """;
        startServerReturning(fixture, 200);

        List<ReviewThread> threads = client.getUnresolvedThreads("octocat", "hello-world", 1);
        assertTrue(threads.isEmpty());
    }

    @Test
    void getUnresolvedThreads_httpError_throws() throws Exception {
        startServerReturning("{\"message\":\"Server Error\"}", 500);
        assertThrows(RuntimeException.class, () -> client.getUnresolvedThreads("octocat", "hello-world", 1));
    }

    @Test
    void getUnresolvedThreads_nullPullRequestWithGraphqlErrors_throws() throws Exception {
        // Real GitHub behavior for a bad PR number: pullRequest is JSON null
        // alongside a populated "errors" array — not an absent field.
        String fixture = """
                {
                  "data": { "repository": { "pullRequest": null } },
                  "errors": [ { "message": "Could not resolve to a PullRequest with the number of 999." } ]
                }
                """;
        startServerReturning(fixture, 200);
        assertThrows(RuntimeException.class, () -> client.getUnresolvedThreads("octocat", "hello-world", 999));
    }

    @Test
    void resolveThread_success_doesNotThrow() throws Exception {
        String fixture = "{\"data\":{\"resolveReviewThread\":{\"thread\":{\"id\":\"RT_1\",\"isResolved\":true}}}}";
        startServerReturning(fixture, 200);
        assertDoesNotThrow(() -> client.resolveThread("RT_1"));
    }

    @Test
    void resolveThread_notResolvedInResponse_throws() throws Exception {
        String fixture = """
                {
                  "data": { "resolveReviewThread": { "thread": { "id": "RT_1", "isResolved": false } } },
                  "errors": [ { "message": "permission denied" } ]
                }
                """;
        startServerReturning(fixture, 200);
        assertThrows(RuntimeException.class, () -> client.resolveThread("RT_1"));
    }
}
