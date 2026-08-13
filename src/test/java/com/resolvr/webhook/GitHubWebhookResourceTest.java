package com.resolvr.webhook;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Exercises the webhook endpoint end-to-end through the real HTTP layer.
 * The secret ("test-secret") comes from src/test/resources/application.properties (%test profile).
 */
@QuarkusTest
class GitHubWebhookResourceTest {

    private static final String SECRET = "test-secret";

    private static final String PULL_REQUEST_REVIEW_PAYLOAD = """
            {
              "action": "submitted",
              "review": {
                "state": "commented",
                "pull_request_url": "https://api.github.com/repos/octocat/hello-world/pulls/42"
              },
              "pull_request": { "number": 42 },
              "repository": { "full_name": "octocat/hello-world" }
            }
            """;

    private static final String REVIEW_COMMENT_PAYLOAD = """
            {
              "action": "created",
              "comment": { "body": "please add a null check here" },
              "pull_request": { "number": 7 },
              "repository": { "full_name": "octocat/hello-world" }
            }
            """;

    private static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int currentPendingCount() {
        return given()
                .when().get("/webhook/status")
                .then().statusCode(200)
                .extract().path("pending_events");
    }

    @Test
    void validSignature_pullRequestReview_isQueued() {
        int before = currentPendingCount();

        given()
                .contentType(ContentType.JSON)
                .header("X-GitHub-Event", "pull_request_review")
                .header("X-GitHub-Delivery", "test-delivery-1")
                .header("X-Hub-Signature-256", sign(PULL_REQUEST_REVIEW_PAYLOAD, SECRET))
                .body(PULL_REQUEST_REVIEW_PAYLOAD)
                .when().post("/webhook/github")
                .then()
                .statusCode(200)
                .body("status", equalTo("queued"));

        int after = currentPendingCount();
        org.junit.jupiter.api.Assertions.assertEquals(before + 1, after);
    }

    @Test
    void validSignature_reviewComment_isQueued() {
        int before = currentPendingCount();

        given()
                .contentType(ContentType.JSON)
                .header("X-GitHub-Event", "pull_request_review_comment")
                .header("X-GitHub-Delivery", "test-delivery-2")
                .header("X-Hub-Signature-256", sign(REVIEW_COMMENT_PAYLOAD, SECRET))
                .body(REVIEW_COMMENT_PAYLOAD)
                .when().post("/webhook/github")
                .then()
                .statusCode(200)
                .body("status", equalTo("queued"));

        int after = currentPendingCount();
        org.junit.jupiter.api.Assertions.assertEquals(before + 1, after);
    }

    @Test
    void invalidSignature_isRejectedWith401() {
        int before = currentPendingCount();

        given()
                .contentType(ContentType.JSON)
                .header("X-GitHub-Event", "pull_request_review")
                .header("X-GitHub-Delivery", "test-delivery-3")
                .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
                .body(PULL_REQUEST_REVIEW_PAYLOAD)
                .when().post("/webhook/github")
                .then()
                .statusCode(401);

        // rejected before dispatch — queue must not have grown
        org.junit.jupiter.api.Assertions.assertEquals(before, currentPendingCount());
    }

    @Test
    void missingSignatureHeader_isRejectedWith401() {
        given()
                .contentType(ContentType.JSON)
                .header("X-GitHub-Event", "pull_request_review")
                .header("X-GitHub-Delivery", "test-delivery-4")
                .body(PULL_REQUEST_REVIEW_PAYLOAD)
                .when().post("/webhook/github")
                .then()
                .statusCode(401);
    }

    @Test
    void validSignature_malformedJson_returns400() {
        String badBody = "{not valid json";
        given()
                .contentType(ContentType.JSON)
                .header("X-GitHub-Event", "pull_request_review")
                .header("X-GitHub-Delivery", "test-delivery-5")
                .header("X-Hub-Signature-256", sign(badBody, SECRET))
                .body(badBody)
                .when().post("/webhook/github")
                .then()
                .statusCode(400);
    }

    @Test
    void unhandledEventType_isAcceptedButNotQueued() {
        int before = currentPendingCount();
        String body = "{\"zen\":\"Keep it logically awesome.\"}";

        given()
                .contentType(ContentType.JSON)
                .header("X-GitHub-Event", "ping")
                .header("X-GitHub-Delivery", "test-delivery-6")
                .header("X-Hub-Signature-256", sign(body, SECRET))
                .body(body)
                .when().post("/webhook/github")
                .then()
                .statusCode(200);

        org.junit.jupiter.api.Assertions.assertEquals(before, currentPendingCount());
    }

    @Test
    void manualTrigger_requiresOwnerRepoPr() {
        given()
                .when().post("/webhook/trigger")
                .then()
                .statusCode(400);
    }

    @Test
    void manualTrigger_withValidParams_queuesEvent() {
        int before = currentPendingCount();

        given()
                .queryParam("owner", "octocat")
                .queryParam("repo", "hello-world")
                .queryParam("pr", 99)
                .when().post("/webhook/trigger")
                .then()
                .statusCode(200)
                .body("status", equalTo("queued"));

        org.junit.jupiter.api.Assertions.assertEquals(before + 1, currentPendingCount());
    }
}
