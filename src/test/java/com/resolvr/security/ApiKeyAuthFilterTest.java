package com.resolvr.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

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

    public static class WithApiKey implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("resolvr.api-key", API_KEY);
        }
    }

    @Test
    void protectedRoute_withoutAuthorizationHeader_returns401() {
        given()
                .when().get("/q/openapi")
                .then().statusCode(401);
    }

    @Test
    void protectedRoute_withWrongKey_returns401() {
        given()
                .header("Authorization", "Bearer wrong-key")
                .when().get("/q/openapi")
                .then().statusCode(401);
    }

    @Test
    void protectedRoute_withCorrectKey_returns200() {
        given()
                .header("Authorization", "Bearer " + API_KEY)
                .when().get("/q/openapi")
                .then().statusCode(200);
    }

    @Test
    void healthCheck_isExemptFromApiKey() {
        given()
                .when().get("/q/health")
                .then().statusCode(200);
    }
}
