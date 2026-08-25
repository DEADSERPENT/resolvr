package com.resolvr.cli.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A small text file recording the PID (and a couple of extra fields used to detect PID
 * reuse) of whichever process `resolvr start`/`dev` most recently spawned. Three lines:
 * PID, a human-readable marker describing what was launched, and the process's own
 * reported start instant (epoch millis) if the OS exposed one — used to tell "the process
 * we started" apart from "an unrelated process the OS later reused this PID for."
 */
public final class PidFile {

    public record Entry(long pid, String marker, Long startEpochMilli) {
    }

    private final Path path;

    public PidFile(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    public void write(long pid, String marker, Long startEpochMilli) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        String content = pid + "\n" + marker + "\n" + (startEpochMilli == null ? "" : startEpochMilli) + "\n";
        Files.writeString(path, content);
    }

    public Optional<Entry> read() {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty()) {
                return Optional.empty();
            }
            long pid = Long.parseLong(lines.get(0).trim());
            String marker = lines.size() > 1 ? lines.get(1) : "";
            Long startEpochMilli = null;
            if (lines.size() > 2 && !lines.get(2).isBlank()) {
                try {
                    startEpochMilli = Long.parseLong(lines.get(2).trim());
                } catch (NumberFormatException ignored) {
                    // malformed timestamp field — treat as absent rather than failing the whole read
                }
            }
            return Optional.of(new Entry(pid, marker, startEpochMilli));
        } catch (IOException | NumberFormatException e) {
            // Malformed or unreadable PID file — treat the same as "no PID file" rather than
            // throwing, since callers (start/stop/status) all need to handle "nothing recorded"
            // anyway and a corrupt file shouldn't be a harder failure than a missing one.
            return Optional.empty();
        }
    }

    public void delete() throws IOException {
        Files.deleteIfExists(path);
    }
}
