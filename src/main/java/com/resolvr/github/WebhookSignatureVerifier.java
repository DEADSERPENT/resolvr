package com.resolvr.github;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@ApplicationScoped
public class WebhookSignatureVerifier {

    @ConfigProperty(name = "github.webhook.secret")
    Optional<String> webhookSecret;

    public boolean verify(String payload, String signatureHeader) {
        if (webhookSecret.isEmpty() || webhookSecret.get().isBlank()) {
            return true; // no secret configured — skip verification (dev mode)
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            String expected = signatureHeader.substring("sha256=".length());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.get().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(digest);
            return MessageDigest.isEqual(computed.getBytes(), expected.getBytes());
        } catch (Exception e) {
            return false;
        }
    }
}
