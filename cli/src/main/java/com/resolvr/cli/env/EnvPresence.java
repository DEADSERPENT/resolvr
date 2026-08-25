package com.resolvr.cli.env;

import java.util.Map;

/**
 * Reports whether a credential-bearing environment variable is SET — never its value.
 * Takes an explicit {@code Map<String,String>} rather than reading {@link System#getenv()}
 * directly so callers can inject a fake environment in tests without touching real env vars,
 * and so it's structurally impossible for this class to ever hold a real secret value: it
 * only ever computes and returns a boolean.
 */
public final class EnvPresence {

    private EnvPresence() {
    }

    public record Check(String variableName, boolean present) {
        @Override
        public String toString() {
            return variableName + ": " + (present ? "set" : "not set");
        }
    }

    public static Check check(Map<String, String> env, String variableName) {
        String value = env.get(variableName);
        return new Check(variableName, value != null && !value.isBlank());
    }

    public static Check checkCurrentEnvironment(String variableName) {
        return check(System.getenv(), variableName);
    }
}
