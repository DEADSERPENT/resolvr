package com.resolvr.webhook;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Runs under a dedicated profile that sets resolvr.api-key, isolated from the
 * rest of the suite (which relies on the key being unset). Quarkus boots a
 * separate app instance for this profile, so it doesn't affect other tests.
 */
@QuarkusTest
@TestProfile(ApiKeyAuthFilterTest.WithApiKey.class)
class ApiKeyAuthFilterTest {

    private static final String API_KEY = "super-secret-test-key";
    // inherited from src/test/resources/application.properties (%test profile)
    private static final String WEBHOOK_SECRET = "test-secret";

    public static class WithApiKey implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("resolvr.api-key", API_KEY);
        }
    }

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

    @Test
    void protectedRoute_withoutAuthorizationHeader_returns401() {
        given()
                .when().get("/webhook/status")
                .then().statusCode(401);
    }

    @Test
    void protectedRoute_withWrongKey_returns401() {
        given()
                .header("Authorization", "Bearer wrong-key")
                .when().get("/webhook/status")
                .then().statusCode(401);
    }

    @Test
    void protectedRoute_withCorrectKey_returns200() {
        given()
                .header("Authorization", "Bearer " + API_KEY)
                .when().get("/webhook/status")
                .then().statusCode(200);
    }

    @Test
    void manualTrigger_withoutKey_isRejectedBeforeReachingHandler() {
        given()
                .queryParam("owner", "octocat")
                .queryParam("repo", "hello-world")
                .queryParam("pr", 1)
                .when().post("/webhook/trigger")
                .then().statusCode(401);
    }

    @Test
    void githubWebhookRoute_isExemptFromApiKeyButStillNeedsValidSignature() {
        String body = "{\"zen\":\"Keep it logically awesome.\"}";
        given()
                .contentType(ContentType.JSON)
                .header("X-GitHub-Event", "ping")
                .header("X-GitHub-Delivery", "apikey-exempt-test")
                .header("X-Hub-Signature-256", sign(body, WEBHOOK_SECRET))
                .body(body)
                // deliberately no Authorization header
                .when().post("/webhook/github")
                .then().statusCode(200);
    }

    @Test
    void githubWebhookRoute_stillRejectsBadSignature_evenThoughExemptFromApiKey() {
        String body = "{\"zen\":\"tampered\"}";
        given()
                .contentType(ContentType.JSON)
                .header("X-GitHub-Event", "ping")
                .header("X-GitHub-Delivery", "apikey-exempt-test-2")
                .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
                .body(body)
                .when().post("/webhook/github")
                .then().statusCode(401);
    }

    @Test
    void healthCheck_isExemptFromApiKey() {
        given()
                .when().get("/q/health")
                .then().statusCode(200);
    }
}
