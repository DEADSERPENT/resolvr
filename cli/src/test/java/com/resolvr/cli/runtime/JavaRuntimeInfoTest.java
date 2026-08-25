package com.resolvr.cli.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaRuntimeInfoTest {

    @Test
    void meetsMinimum_trueAtExactMinimum() {
        assertTrue(JavaRuntimeInfo.of(21, "21.0.1", "Eclipse Adoptium").meetsMinimum());
    }

    @Test
    void meetsMinimum_trueAboveMinimum() {
        assertTrue(JavaRuntimeInfo.of(23, "23", "Eclipse Adoptium").meetsMinimum());
    }

    @Test
    void meetsMinimum_falseBelowMinimum() {
        assertFalse(JavaRuntimeInfo.of(17, "17.0.9", "Eclipse Adoptium").meetsMinimum());
    }

    @Test
    void detectCurrent_reportsTheJvmActuallyRunningTheTests() {
        JavaRuntimeInfo info = JavaRuntimeInfo.detectCurrent();
        assertTrue(info.featureVersion() >= 21, "test suite itself requires Java 21+");
        assertTrue(info.meetsMinimum());
        assertNotNull(info.versionString());
        assertNotNull(info.vendor());
    }
}
