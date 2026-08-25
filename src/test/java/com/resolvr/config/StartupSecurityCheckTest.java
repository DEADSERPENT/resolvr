package com.resolvr.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises StartupSecurityCheck.problems() directly — the pure validation logic behind
 * the fail-closed StartupEvent guard. Not a @QuarkusTest: LaunchMode.current() always
 * reports TEST inside one, so the guard itself is never reachable there; this tests what
 * it decides once LaunchMode.NORMAL is established, without needing to fake that.
 */
class StartupSecurityCheckTest {

    @Test
    void noApiKey_isAProblem() {
        List<String> problems = StartupSecurityCheck.problems(null);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("resolvr.api-key"));
    }

    @Test
    void blankApiKey_isAProblem() {
        List<String> problems = StartupSecurityCheck.problems("  ");
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("resolvr.api-key"));
    }

    @Test
    void apiKeySet_noProblems() {
        assertTrue(StartupSecurityCheck.problems("some-key").isEmpty());
    }
}
