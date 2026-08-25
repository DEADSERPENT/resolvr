package com.resolvr.security;

import io.quarkus.logging.Log;
import io.quarkus.vertx.http.runtime.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;

/**
 * Gates every route except the health check behind a shared API key.
 *
 * Without this, /mcp/sse has zero authentication — anyone who can reach the port can drive
 * this server's GitHub token. An unset key means "dev mode, stay open" so a bare
 * `./scripts/run.sh` on localhost keeps working with no extra setup — but {@link
 * com.resolvr.config.StartupSecurityCheck} refuses to boot a packaged (non-dev, non-test)
 * instance without a key configured, so that open state can't reach a real deployment.
 */
@ApplicationScoped
public class ApiKeyAuthFilter {

    @ConfigProperty(name = "resolvr.api-key")
    Optional<String> apiKey;

    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/q/health",
            "/q/health/live",
            "/q/health/ready"
    );

    void registerFilter(@Observes Filters filters) {
        filters.register(rc -> {
            if (apiKey.isEmpty() || apiKey.get().isBlank()) {
                rc.next(); // no key configured — open (dev mode)
                return;
            }
            if (EXEMPT_PATHS.contains(rc.normalizedPath())) {
                rc.next();
                return;
            }

            String header = rc.request().getHeader("Authorization");
            if (header != null && isValid(header, apiKey.get())) {
                rc.next();
                return;
            }

            Log.warnf("Rejected unauthenticated request to %s", rc.normalizedPath());
            rc.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(401)
                    .end("{\"error\":\"missing or invalid API key\"}");
        }, 100);
    }

    private boolean isValid(String authHeader, String configuredKey) {
        String expected = "Bearer " + configuredKey;
        byte[] a = authHeader.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
