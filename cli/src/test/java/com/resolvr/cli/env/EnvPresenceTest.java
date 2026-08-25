package com.resolvr.cli.env;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvPresenceTest {

    @Test
    void present_whenSetToNonBlankValue() {
        EnvPresence.Check check = EnvPresence.check(Map.of("GITHUB_TOKEN", "ghp_something"), "GITHUB_TOKEN");
        assertTrue(check.present());
    }

    @Test
    void absent_whenKeyMissing() {
        EnvPresence.Check check = EnvPresence.check(Map.of(), "GITHUB_TOKEN");
        assertFalse(check.present());
    }

    @Test
    void absent_whenValueIsBlank() {
        EnvPresence.Check check = EnvPresence.check(Map.of("GITHUB_TOKEN", "   "), "GITHUB_TOKEN");
        assertFalse(check.present());
    }

    @Test
    void toString_neverContainsTheActualValue() {
        String secretValue = "ghp_totallySecretValue12345";
        EnvPresence.Check check = EnvPresence.check(Map.of("GITHUB_TOKEN", secretValue), "GITHUB_TOKEN");
        assertFalse(check.toString().contains(secretValue),
                "EnvPresence.Check must never leak the credential value, even if given one");
    }

    @Test
    void checkCurrentEnvironment_doesNotThrow() {
        assertDoesNotThrow(() -> EnvPresence.checkCurrentEnvironment("SOME_VAR_THAT_PROBABLY_DOES_NOT_EXIST_XYZ"));
    }
}
