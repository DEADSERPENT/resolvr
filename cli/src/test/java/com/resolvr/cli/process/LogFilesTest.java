package com.resolvr.cli.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void missingFile_returnsEmptyList() {
        assertEquals(List.of(), LogFiles.tail(tempDir.resolve("nope.log"), 10));
    }

    @Test
    void fewerLinesThanLimit_returnsAll() throws Exception {
        Path log = tempDir.resolve("resolvr.log");
        Files.writeString(log, "line1\nline2\nline3\n");
        assertEquals(List.of("line1", "line2", "line3"), LogFiles.tail(log, 10));
    }

    @Test
    void moreLinesThanLimit_returnsOnlyTheTail() throws Exception {
        Path log = tempDir.resolve("resolvr.log");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            content.append("line").append(i).append("\n");
        }
        Files.writeString(log, content.toString());

        List<String> tail = LogFiles.tail(log, 5);
        assertEquals(List.of("line46", "line47", "line48", "line49", "line50"), tail);
    }

    @Test
    void handlesWindowsLineEndings() throws Exception {
        Path log = tempDir.resolve("resolvr.log");
        Files.writeString(log, "line1\r\nline2\r\n");
        assertEquals(List.of("line1", "line2"), LogFiles.tail(log, 10));
    }

    @Test
    void invalidUtf8Bytes_areDecodedLeniently_notThrown() throws Exception {
        Path log = tempDir.resolve("resolvr.log");
        // A byte sequence that is not valid UTF-8 (a lone continuation byte), sandwiched
        // between two normal lines — mirrors a real server log line containing a character
        // that wasn't written as valid UTF-8 under the process's console encoding.
        byte[] validPrefix = "before\n".getBytes(StandardCharsets.UTF_8);
        byte[] invalid = new byte[]{(byte) 0x80, (byte) 0x81};
        byte[] validSuffix = "\nafter\n".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[validPrefix.length + invalid.length + validSuffix.length];
        System.arraycopy(validPrefix, 0, all, 0, validPrefix.length);
        System.arraycopy(invalid, 0, all, validPrefix.length, invalid.length);
        System.arraycopy(validSuffix, 0, all, validPrefix.length + invalid.length, validSuffix.length);
        Files.write(log, all);

        List<String> tail = assertDoesNotThrow(() -> LogFiles.tail(log, 10));
        assertEquals(3, tail.size());
        assertEquals("before", tail.get(0));
        assertEquals("after", tail.get(2));
    }
}
