package com.resolvr.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit5 unit tests — no Quarkus context needed since the verifier is a
 * pure function of (payload, signature header, configured secret).
 *
 * Test vectors below were computed independently via:
 *   printf '%s' "$PAYLOAD" | openssl dgst -sha256 -hmac "$SECRET"
 * so this test does not just mirror the implementation's own math back at it.
 */
class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-secret";
    private static final String PAYLOAD = "{\"action\":\"submitted\"}";
    private static final String VALID_SIGNATURE =
            "sha256=d0823db5425f6bdcff2b70381e515ba9ba28888a84d705e0254e609ebdc93740";
    private static final String EMPTY_BODY_SIGNATURE =
            "sha256=a41bc6d81d6413576ae0994995e0ad89a416ec97389515c3604f47722122eeeb";

    private WebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new WebhookSignatureVerifier();
    }

    private void withSecret(String secret) {
        verifier.webhookSecret = Optional.ofNullable(secret);
    }

    @Test
    void validSignature_returnsTrue() {
        withSecret(SECRET);
        assertTrue(verifier.verify(PAYLOAD, VALID_SIGNATURE));
    }

    @Test
    void tamperedPayload_returnsFalse() {
        withSecret(SECRET);
        assertFalse(verifier.verify(PAYLOAD + "tampered", VALID_SIGNATURE));
    }

    @Test
    void wrongSecret_returnsFalse() {
        withSecret("a-different-secret");
        assertFalse(verifier.verify(PAYLOAD, VALID_SIGNATURE));
    }

    @Test
    void malformedHeader_missingSha256Prefix_returnsFalse() {
        withSecret(SECRET);
        assertFalse(verifier.verify(PAYLOAD, "d0823db5425f6bdcff2b70381e515ba9ba28888a84d705e0254e609ebdc93740"));
    }

    @Test
    void nullSignatureHeader_returnsFalse() {
        withSecret(SECRET);
        assertFalse(verifier.verify(PAYLOAD, null));
    }

    @Test
    void garbageSignatureValue_returnsFalse() {
        withSecret(SECRET);
        assertFalse(verifier.verify(PAYLOAD, "sha256=not-valid-hex"));
    }

    @Test
    void emptyBody_withMatchingSignature_returnsTrue() {
        withSecret(SECRET);
        assertTrue(verifier.verify("", EMPTY_BODY_SIGNATURE));
    }

    @Test
    void noSecretConfigured_bypassesVerification() {
        withSecret(null);
        assertTrue(verifier.verify(PAYLOAD, "sha256=anything-or-even-garbage"));
        assertTrue(verifier.verify(PAYLOAD, null));
    }

    @Test
    void blankSecretConfigured_bypassesVerification() {
        withSecret("   ");
        assertTrue(verifier.verify(PAYLOAD, null));
    }
}
