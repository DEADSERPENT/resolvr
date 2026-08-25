package com.resolvr.cli.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PidFileTest {

    @TempDir
    Path tempDir;

    @Test
    void writeThenRead_roundTrips() throws Exception {
        PidFile pidFile = new PidFile(tempDir.resolve("nested").resolve("resolvr.pid"));
        pidFile.write(12345L, "packaged-jar", 999_000_000L);

        Optional<PidFile.Entry> entry = pidFile.read();
        assertTrue(entry.isPresent());
        assertEquals(12345L, entry.get().pid());
        assertEquals("packaged-jar", entry.get().marker());
        assertEquals(999_000_000L, entry.get().startEpochMilli());
    }

    @Test
    void write_createsParentDirectories() throws Exception {
        Path nested = tempDir.resolve("a").resolve("b").resolve("c").resolve("resolvr.pid");
        new PidFile(nested).write(1L, "m", null);
        assertTrue(Files.isRegularFile(nested));
    }

    @Test
    void read_missingFile_returnsEmpty() {
        PidFile pidFile = new PidFile(tempDir.resolve("does-not-exist.pid"));
        assertTrue(pidFile.read().isEmpty());
    }

    @Test
    void read_emptyFile_returnsEmpty() throws Exception {
        Path path = tempDir.resolve("empty.pid");
        Files.writeString(path, "");
        assertTrue(new PidFile(path).read().isEmpty());
    }

    @Test
    void read_malformedPid_returnsEmptyRatherThanThrowing() throws Exception {
        Path path = tempDir.resolve("malformed.pid");
        Files.writeString(path, "not-a-number\nsome-marker\n");
        assertDoesNotThrow(() -> {
            Optional<PidFile.Entry> entry = new PidFile(path).read();
            assertTrue(entry.isEmpty());
        });
    }

    @Test
    void read_missingStartTime_isTreatedAsAbsentNotAnError() throws Exception {
        Path path = tempDir.resolve("no-start.pid");
        Files.writeString(path, "42\nsome-marker\n");
        Optional<PidFile.Entry> entry = new PidFile(path).read();
        assertTrue(entry.isPresent());
        assertEquals(42L, entry.get().pid());
        assertNull(entry.get().startEpochMilli());
    }

    @Test
    void read_malformedStartTime_isTreatedAsAbsentNotAnError() throws Exception {
        Path path = tempDir.resolve("bad-start.pid");
        Files.writeString(path, "42\nsome-marker\nnot-a-timestamp\n");
        Optional<PidFile.Entry> entry = new PidFile(path).read();
        assertTrue(entry.isPresent());
        assertEquals(42L, entry.get().pid());
        assertNull(entry.get().startEpochMilli());
    }

    @Test
    void delete_missingFile_doesNotThrow() {
        PidFile pidFile = new PidFile(tempDir.resolve("nope.pid"));
        assertDoesNotThrow(pidFile::delete);
    }

    @Test
    void delete_removesTheFile() throws Exception {
        PidFile pidFile = new PidFile(tempDir.resolve("resolvr.pid"));
        pidFile.write(1L, "m", null);
        assertTrue(Files.exists(pidFile.path()));
        pidFile.delete();
        assertFalse(Files.exists(pidFile.path()));
    }
}
