package com.resolvr.cli.launch;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JavaExecutableLocatorTest {

    @Test
    void prefersCurrentProcessCommand_whenPresent() {
        String result = JavaExecutableLocator.locate(Optional.of("/opt/jdk/bin/java"), "/opt/jdk", "Linux");
        assertEquals("/opt/jdk/bin/java", result);
    }

    @Test
    void fallsBackToJavaHome_windows_whenProcessCommandAbsent() {
        String result = JavaExecutableLocator.locate(Optional.empty(), "C:\\jdk", "Windows 11");
        assertEquals(java.nio.file.Path.of("C:\\jdk", "bin", "java.exe").toString(), result);
    }

    @Test
    void fallsBackToJavaHome_unix_whenProcessCommandAbsent() {
        String result = JavaExecutableLocator.locate(Optional.empty(), "/opt/jdk", "Linux");
        assertEquals(java.nio.file.Path.of("/opt/jdk", "bin", "java").toString(), result);
    }

    @Test
    void blankProcessCommand_fallsBackToJavaHome() {
        String result = JavaExecutableLocator.locate(Optional.of("  "), "/opt/jdk", "Mac OS X");
        assertEquals(java.nio.file.Path.of("/opt/jdk", "bin", "java").toString(), result);
    }

    @Test
    void locateCurrent_doesNotThrow() {
        assertDoesNotThrow(JavaExecutableLocator::locateCurrent);
    }
}
