package com.resolvr.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resolvr.model.ReviewThread;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class GitHubGraphQLClient {

    @ConfigProperty(name = "github.graphql.url", defaultValue = "https://api.github.com/graphql")
    String graphqlUrl;

    @ConfigProperty(name = "github.token")
    Optional<String> githubToken;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── Fetch unresolved review threads ──────────────────────────────────────

    private static final String FETCH_THREADS_QUERY = """
            query($owner: String!, $repo: String!, $pr: Int!, $cursor: String) {
              repository(owner: $owner, name: $repo) {
                pullRequest(number: $pr) {
                  headRefName
                  reviewThreads(first: 100, after: $cursor) {
                    pageInfo { hasNextPage endCursor }
                    nodes {
                      id
                      isResolved
                      comments(first: 1) {
                        nodes {
                          body
                          path
                          line
                          originalLine
                          author { login }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    public List<ReviewThread> getUnresolvedThreads(String owner, String repo, int prNumber) throws Exception {
        return fetchReviewThreads(owner, repo, prNumber, true);
    }

    /** Both resolved and unresolved threads, each tagged with its resolution state — used by the PR Context Engine. */
    public List<ReviewThread> getAllReviewThreads(String owner, String repo, int prNumber) throws Exception {
        return fetchReviewThreads(owner, repo, prNumber, false);
    }

    private List<ReviewThread> fetchReviewThreads(String owner, String repo, int prNumber, boolean unresolvedOnly)
            throws Exception {
        List<ReviewThread> result = new ArrayList<>();
        String branch = null;
        String cursor = null;
        boolean hasNextPage = true;

        // reviewThreads is paginated by GitHub — a PR with more than 100 threads
        // requires walking pageInfo.endCursor until hasNextPage is false.
        while (hasNextPage) {
            Map<String, Object> variables = new HashMap<>();
            variables.put("owner", owner);
            variables.put("repo", repo);
            variables.put("pr", prNumber);
            variables.put("cursor", cursor);

            JsonNode root = executeGraphQL(FETCH_THREADS_QUERY, variables);

            JsonNode pr = root.path("data").path("repository").path("pullRequest");
            if (pr.isMissingNode() || pr.isNull()) {
                throwGraphQLErrors(root);
                throw new RuntimeException("GitHub GraphQL returned no pull request for "
                        + owner + "/" + repo + "#" + prNumber);
            }

            if (branch == null) {
                branch = pr.path("headRefName").asText();
            }

            JsonNode reviewThreads = pr.path("reviewThreads");
            for (JsonNode thread : reviewThreads.path("nodes")) {
                boolean resolved = thread.path("isResolved").asBoolean();
                if (unresolvedOnly && resolved) continue;

                JsonNode comment = thread.path("comments").path("nodes").get(0);
                if (comment == null) continue;

                int line = comment.has("line") && !comment.path("line").isNull()
                        ? comment.path("line").asInt()
                        : comment.path("originalLine").asInt();

                result.add(new ReviewThread(
                        thread.path("id").asText(),
                        comment.path("path").asText(),
                        line,
                        comment.path("body").asText(),
                        comment.path("author").path("login").asText("github-copilot"),
                        branch,
                        owner,
                        repo,
                        prNumber,
                        resolved
                ));
            }

            JsonNode pageInfo = reviewThreads.path("pageInfo");
            hasNextPage = pageInfo.path("hasNextPage").asBoolean(false);
            cursor = pageInfo.path("endCursor").asText(null);
            if (cursor == null) {
                hasNextPage = false; // guard against a malformed response looping forever
            }
        }

        Log.infof("Found %d %sthreads for %s/%s#%d", result.size(),
                unresolvedOnly ? "unresolved " : "", owner, repo, prNumber);
        return result;
    }

    // ─── Resolve a single review thread ───────────────────────────────────────

    private static final String RESOLVE_MUTATION = """
            mutation($threadId: ID!) {
              resolveReviewThread(input: {threadId: $threadId}) {
                thread { id isResolved }
              }
            }
            """;

    public void resolveThread(String threadId) throws Exception {
        JsonNode root = executeGraphQL(RESOLVE_MUTATION, Map.of("threadId", threadId));
        boolean resolved = root.path("data").path("resolveReviewThread").path("thread").path("isResolved").asBoolean();
        if (!resolved) {
            throwGraphQLErrors(root);
        }
        Log.infof("Resolved thread %s", threadId);
    }

    // ─── Batch resolve multiple threads ───────────────────────────────────────

    public void resolveThreadsBatch(List<String> threadIds) throws Exception {
        for (int i = 1; i < threadIds.size(); i++) {
            String id = threadIds.get(i);
            try {
                resolveThread(id);
            } catch (Exception e) {
                Log.warnf("Failed to resolve thread %s: %s", id, e.getMessage());
            }
        }
    }

    // ─── Internal GraphQL executor ─────────────────────────────────────────────

    private JsonNode executeGraphQL(String query, Map<String, Object> variables) throws Exception {
        String body = mapper.writeValueAsString(Map.of("query", query, "variables", variables));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(graphqlUrl))
                .header("Authorization", "Bearer " + GitHubTokenResolver.resolve(githubToken.orElse(null)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Github-Next-Global-ID", "1")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = RetryingHttpSender.send(http, req);

        if (resp.statusCode() != 200) {
            throw new RuntimeException("GitHub GraphQL returned HTTP " + resp.statusCode() + ": " + resp.body());
        }

        return mapper.readTree(resp.body());
    }

    private void throwGraphQLErrors(JsonNode root) {
        JsonNode errors = root.path("errors");
        if (!errors.isMissingNode() && errors.isArray() && !errors.isEmpty()) {
            throw new RuntimeException("GraphQL errors: " + errors.toString());
        }
    }
}
