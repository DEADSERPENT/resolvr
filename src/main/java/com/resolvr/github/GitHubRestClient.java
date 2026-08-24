package com.resolvr.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resolvr.model.ChangedFile;
import com.resolvr.model.CheckRun;
import com.resolvr.model.CommitInfo;
import com.resolvr.model.PrComment;
import com.resolvr.model.PullRequestMetadata;
import com.resolvr.model.PullRequestSummary;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class GitHubRestClient {

    @ConfigProperty(name = "github.api.base-url", defaultValue = "https://api.github.com")
    String apiBase;

    @ConfigProperty(name = "github.token")
    Optional<String> githubToken;

    // GitHub 301-redirects renamed repositories (e.g. repos/{owner}/{old-name} ->
    // repositories/{id}) — without following redirects, the 3xx's JSON body gets
    // parsed as if it were the actual response (e.g. a bare object misread as a
    // PR list), which is silently wrong rather than an obvious error.
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
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
        post(url, Map.of("body", body, "in_reply_to_id", inReplyToId));
    }

    // ─── Get PR head branch ───────────────────────────────────────────────────

    public String getPRHeadBranch(String owner, String repo, int prNumber) throws Exception {
        String url = apiBase + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
        return get(url).path("head").path("ref").asText();
    }

    // ─── List open PRs whose head branch matches (for workspace → PR discovery) ─

    public List<PullRequestSummary> listOpenPullRequests(String owner, String repo, String headBranch) throws Exception {
        String headParam = URLEncoder.encode(owner + ":" + headBranch, StandardCharsets.UTF_8);
        String url = apiBase + "/repos/" + owner + "/" + repo + "/pulls?state=open&head=" + headParam;
        JsonNode results = get(url);
        if (!results.isArray()) {
            throw new GitHubApiException(200, "Expected a JSON array of pull requests, got: " + results);
        }

        List<PullRequestSummary> summaries = new ArrayList<>();
        for (JsonNode pr : results) {
            summaries.add(new PullRequestSummary(
                    pr.path("number").asInt(),
                    pr.path("title").asText(),
                    pr.path("base").path("ref").asText(),
                    pr.path("head").path("ref").asText(),
                    pr.path("head").path("sha").asText(),
                    pr.path("state").asText(),
                    pr.path("html_url").asText(null)
            ));
        }
        return summaries;
    }

    // ─── Full PR metadata (PR Context Engine) ──────────────────────────────────

    public PullRequestMetadata getPullRequest(String owner, String repo, int prNumber) throws Exception {
        String url = apiBase + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
        JsonNode pr = get(url);
        return new PullRequestMetadata(
                pr.path("number").asInt(),
                pr.path("title").asText(null),
                pr.path("body").asText(null),
                pr.path("state").asText(null),
                pr.path("user").path("login").asText(null),
                pr.path("base").path("ref").asText(null),
                pr.path("head").path("ref").asText(null),
                pr.path("head").path("sha").asText(null),
                pr.path("created_at").asText(null),
                pr.path("updated_at").asText(null),
                pr.path("html_url").asText(null)
        );
    }

    // ─── Changed files (paginated) ─────────────────────────────────────────────

    public List<ChangedFile> listChangedFiles(String owner, String repo, int prNumber) throws Exception {
        String base = apiBase + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/files";
        List<ChangedFile> files = new ArrayList<>();
        for (JsonNode f : paginateArray(base)) {
            files.add(new ChangedFile(
                    f.path("filename").asText(null),
                    f.path("additions").asInt(),
                    f.path("deletions").asInt(),
                    f.path("status").asText(null),
                    f.path("sha").asText(null)
            ));
        }
        return files;
    }

    // ─── Commits on the PR (paginated) ──────────────────────────────────────────

    public List<CommitInfo> listCommits(String owner, String repo, int prNumber) throws Exception {
        String base = apiBase + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/commits";
        List<CommitInfo> commits = new ArrayList<>();
        for (JsonNode c : paginateArray(base)) {
            JsonNode commit = c.path("commit");
            commits.add(new CommitInfo(
                    c.path("sha").asText(null),
                    commit.path("message").asText(null),
                    commit.path("author").path("name").asText(null),
                    commit.path("author").path("date").asText(null)
            ));
        }
        return commits;
    }

    // ─── PR/issue comments (paginated) ──────────────────────────────────────────

    public List<PrComment> listIssueComments(String owner, String repo, int prNumber) throws Exception {
        String base = apiBase + "/repos/" + owner + "/" + repo + "/issues/" + prNumber + "/comments";
        List<PrComment> comments = new ArrayList<>();
        for (JsonNode c : paginateArray(base)) {
            comments.add(new PrComment(
                    c.path("user").path("login").asText(null),
                    c.path("body").asText(null),
                    c.path("created_at").asText(null)
            ));
        }
        return comments;
    }

    // ─── Raw unified diff ────────────────────────────────────────────────────────

    public String getDiff(String owner, String repo, int prNumber) throws Exception {
        String url = apiBase + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
        HttpRequest req = request(url)
                .setHeader("Accept", "application/vnd.github.v3.diff")
                .GET()
                .build();
        HttpResponse<String> resp = RetryingHttpSender.send(http, req);
        if (resp.statusCode() >= 400) {
            throw new GitHubApiException(resp.statusCode(), resp.body());
        }
        return resp.body();
    }

    // ─── CI/check status for a commit (paginated) ───────────────────────────────

    public List<CheckRun> listCheckRuns(String owner, String repo, String ref) throws Exception {
        String base = apiBase + "/repos/" + owner + "/" + repo + "/commits/" + ref + "/check-runs";
        List<CheckRun> runs = new ArrayList<>();
        int page = 1;
        while (true) {
            JsonNode root = get(base + "?per_page=100&page=" + page);
            JsonNode arr = root.path("check_runs");
            if (!arr.isArray() || arr.isEmpty()) break;
            for (JsonNode c : arr) {
                runs.add(new CheckRun(
                        c.path("name").asText(null),
                        c.path("status").asText(null),
                        c.path("conclusion").asText(null),
                        c.path("html_url").asText(null)
                ));
            }
            if (arr.size() < 100) break;
            page++;
        }
        return runs;
    }

    // ─── Generic "GET a bare JSON array, page until short of per_page" helper ───

    private List<JsonNode> paginateArray(String baseUrl) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        int page = 1;
        while (true) {
            String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "per_page=100&page=" + page;
            JsonNode arr = get(url);
            if (!arr.isArray() || arr.isEmpty()) break;
            arr.forEach(all::add);
            if (arr.size() < 100) break;
            page++;
        }
        return all;
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
                .header("Authorization", "Bearer " + GitHubTokenResolver.resolve(githubToken.orElse(null)))
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
