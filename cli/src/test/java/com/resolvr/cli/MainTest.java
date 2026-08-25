package com.resolvr.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    private String runCapturingOutput(String[] args, int[] exitCodeOut) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        exitCodeOut[0] = Main.run(args, new PrintStream(buffer));
        return buffer.toString();
    }

    @Test
    void noArgs_printsUsageAndReturnsNonZero() {
        int[] exitCode = new int[1];
        String output = runCapturingOutput(new String[0], exitCode);
        assertEquals(1, exitCode[0]);
        assertTrue(output.contains("Usage: resolvr"));
    }

    @Test
    void unknownCommand_printsUsageAndReturnsNonZero() {
        int[] exitCode = new int[1];
        String output = runCapturingOutput(new String[]{"frobnicate"}, exitCode);
        assertEquals(1, exitCode[0]);
        assertTrue(output.contains("Unknown command"));
        assertTrue(output.contains("Usage: resolvr"));
    }

    @Test
    void statusCommand_isRecognizedAndRuns() {
        // Doesn't assert a specific exit code (depends on whether a server happens to be
        // running on this machine's default port) — only that it's dispatched, not treated
        // as unknown, and produces status-shaped output.
        int[] exitCode = new int[1];
        String output = runCapturingOutput(new String[]{"status"}, exitCode);
        assertTrue(output.contains("Resolvr status"));
        assertFalse(output.contains("Unknown command"));
    }
}
