package com.resolvr.cli.repo;

import java.nio.file.Path;

/** Where the CLI keeps its own state for a given repo checkout: PID file and server log,
 * both under {@code <repoRoot>/.resolvr/} (gitignored) — scoped per-checkout since a
 * developer may have more than one Resolvr clone running its own instance. */
public final class ResolvrPaths {

    private ResolvrPaths() {
    }

    public static Path stateDir(Path repoRoot) {
        return repoRoot.resolve(".resolvr");
    }

    public static Path pidFilePath(Path repoRoot) {
        return stateDir(repoRoot).resolve("resolvr.pid");
    }

    public static Path logFilePath(Path repoRoot) {
        return stateDir(repoRoot).resolve("resolvr.log");
    }
}
