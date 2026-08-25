package com.resolvr.cli.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the last N lines of a log file via pure Java NIO — no `tail` shell-out.
 *
 * Decodes leniently (invalid byte sequences become the U+FFFD replacement character)
 * rather than using {@code Files.readAllLines}, which throws on malformed input for the
 * default charset. A server subprocess's console output can legitimately contain bytes
 * that aren't valid UTF-8 under the platform's default charset (observed in practice: a
 * message string containing an em-dash, written under a non-UTF-8 default console encoding
 * on Windows) — a log tail reader crashing on that and silently showing nothing is a worse
 * outcome than a stray replacement character in the displayed text.
 */
public final class LogFiles {

    private LogFiles() {
    }

    public static List<String> tail(Path logFile, int maxLines) {
        try {
            byte[] bytes = Files.readAllBytes(logFile);
            String content = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String rawLine : content.split("\n", -1)) {
                String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
                lines.add(line);
            }
            // split("\n", -1) on content ending in a newline yields a trailing empty
            // element that isn't a real line — drop it so line counts reflect actual output.
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            return lines.size() <= maxLines ? lines : lines.subList(lines.size() - maxLines, lines.size());
        } catch (IOException e) {
            return List.of();
        }
    }
}
