package com.resolvr.config;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fail-closed guard for packaged (non-dev, non-test) startup. Dev and test profiles
 * intentionally ship with a blank api-key so `./mvnw quarkus:dev` and the test suite need
 * no setup — but a real deployment that inherits that same blank default would silently
 * expose a write-capable GitHub bot with zero authentication. Refuse to boot instead.
 */
@ApplicationScoped
public class StartupSecurityCheck {

    @ConfigProperty(name = "resolvr.api-key")
    Optional<String> apiKey;

    void onStart(@Observes StartupEvent ev) {
        if (LaunchMode.current() != LaunchMode.NORMAL) {
            return; // dev/test — relaxed defaults are intentional there
        }
        List<String> problems = problems(apiKey.orElse(null));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: configuration.\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    /** Pure validation logic, split out from the CDI/LaunchMode wiring above so it's unit-testable directly. */
    static List<String> problems(String apiKey) {
        List<String> problems = new ArrayList<>();
        if (apiKey == null || apiKey.isBlank()) {
            problems.add("resolvr.api-key (RESOLVR_API_KEY) is not set — every MCP route would be "
                    + "reachable with no authentication.");
        }
        return problems;
    }
}
