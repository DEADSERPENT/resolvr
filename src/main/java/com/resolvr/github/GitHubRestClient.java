package com.resolvr.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class GitHubRestClient {

    @ConfigProperty(name = "github.api.base-url", defaultValue = "https://api.github.com")
    String apiBase;

    @ConfigProperty(name = "github.token")
    String githubToken;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── Read a file from a branch ────────────────────────────────────────────

    public String getFileContent(String owner, String repo, String branch, String filePath) throws Exception {
        String url = fileUrl(owner, repo, filePath) + "?ref=" + branch;
        JsonNode node = get(url);
        String encoded = node.path("content").asText("").replace("\n", "").replace(" ", "");
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    // ─── Commit a file change to a branch ─────────────────────────────────────

    // GitHub's Contents API hard-limits file content to ~1MB base64-encoded;
    // larger files need the Git Data API (blobs), which this client doesn't implement.
    private static final int MAX_CONTENT_BYTES = 1_000_000;

    public String commitFileChange(String owner, String repo, String branch,
                                   String filePath, String newContent, String commitMessage) throws Exception {
        byte[] contentBytes = newContent.getBytes(StandardCharsets.UTF_8);
        if (contentBytes.length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("File " + filePath + " is " + contentBytes.length
                    + " bytes, over the GitHub Contents API's ~1MB limit — this client can't commit it. "
                    + "Split the change into a smaller diff.");
        }

        String url = fileUrl(owner, repo, filePath);
        String currentSha = getCurrentFileSha(owner, repo, branch, filePath);

        String encodedContent = Base64.getEncoder().encodeToString(contentBytes);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", commitMessage);
        body.put("content", encodedContent);
        body.put("branch", branch);
        if (currentSha != null) {
            body.put("sha", currentSha);
        }

        JsonNode resp;
        try {
            resp = put(url, body);
        } catch (GitHubApiException e) {
            if (e.statusCode() == 409) {
                throw new IllegalStateException("Conflict committing " + filePath + ": the file changed on "
                        + "GitHub since it was last read (stale sha). Call get_file_content again to fetch "
                        + "the latest content, then retry the fix.", e);
            }
            throw e;
        }
        String commitSha = resp.path("commit").path("sha").asText();
        Log.infof("Committed %s on branch %s — sha %s", filePath, branch, commitSha);
        return commitSha;
    }

    // ─── Add a reply comment to a review thread ───────────────────────────────

    public void replyToThread(String owner, String repo, int prNumber,
                              String body, int inReplyToId) throws Exception {
        String url = apiBase + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/comments";
        post(url, Map.of("body", body, "in_reply_to", inReplyToId));
    }

    // ─── Get PR head branch ───────────────────────────────────────────────────

    public String getPRHeadBranch(String owner, String repo, int prNumber) throws Exception {
        String url = apiBase + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
        return get(url).path("head").path("ref").asText();
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private String getCurrentFileSha(String owner, String repo, String branch, String filePath) {
        try {
            String url = fileUrl(owner, repo, filePath) + "?ref=" + branch;
            return get(url).path("sha").asText(null);
        } catch (Exception e) {
            return null; // file doesn't exist yet — creating it
        }
    }

    private String fileUrl(String owner, String repo, String filePath) {
        return apiBase + "/repos/" + owner + "/" + repo + "/contents/" + filePath;
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = request(url).GET().build();
        return execute(req);
    }

    private JsonNode put(String url, Object body) throws Exception {
        HttpRequest req = request(url)
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return execute(req);
    }

    private JsonNode post(String url, Object body) throws Exception {
        HttpRequest req = request(url)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return execute(req);
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30));
    }

    private JsonNode execute(HttpRequest req) throws Exception {
        HttpResponse<String> resp = RetryingHttpSender.send(http, req);
        if (resp.statusCode() >= 400) {
            throw new GitHubApiException(resp.statusCode(), resp.body());
        }
        return mapper.readTree(resp.body());
    }
}
