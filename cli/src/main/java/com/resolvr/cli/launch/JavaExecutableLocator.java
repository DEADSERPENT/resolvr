package com.resolvr.cli.launch;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the path to a `java` executable to spawn the server with — deliberately not just
 * "java" on PATH, since that could silently resolve to a different/older JDK than the one
 * running this CLI. Prefers the exact executable that's currently running the CLI itself
 * (via {@link ProcessHandle#info()}), falling back to {@code java.home} if the OS didn't
 * report it (not all platforms populate {@code ProcessHandle.Info.command()}).
 */
public final class JavaExecutableLocator {

    private JavaExecutableLocator() {
    }

    public static String locateCurrent() {
        return locate(ProcessHandle.current().info().command(), System.getProperty("java.home"),
                System.getProperty("os.name", ""));
    }

    /** Pure variant for tests. */
    public static String locate(Optional<String> currentProcessCommand, String javaHome, String osName) {
        if (currentProcessCommand.isPresent() && !currentProcessCommand.get().isBlank()) {
            return currentProcessCommand.get();
        }
        boolean windows = osName.toLowerCase(java.util.Locale.ROOT).contains("win");
        String exeName = windows ? "java.exe" : "java";
        return Path.of(javaHome, "bin", exeName).toString();
    }
}
