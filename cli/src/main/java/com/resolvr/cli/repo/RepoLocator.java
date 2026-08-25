package com.resolvr.cli.repo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds the Resolvr server repository root (the directory containing the server's pom.xml,
 * not the CLI's own pom.xml under cli/) so start/stop/dev can locate the server's Maven
 * wrapper and packaged jar regardless of the CLI's own install location or the user's
 * current working directory.
 *
 * Resolution order:
 *  1. The {@code resolvr.repo.root} system property, set by the bin/resolvr launcher
 *     scripts (which know their own location — the one genuinely platform-specific fact
 *     this whole module needs, and it's resolved once, in the wrapper, not duplicated
 *     per-command).
 *  2. Walking upward from the starting directory (normally the current working directory)
 *     looking for the server's marker files.
 */
public final class RepoLocator {

    public static final String REPO_ROOT_PROPERTY = "resolvr.repo.root";

    private RepoLocator() {
    }

    public static class RepoNotFoundException extends RuntimeException {
        public RepoNotFoundException(String message) {
            super(message);
        }
    }

    public static Path locate() {
        return locate(System.getProperty(REPO_ROOT_PROPERTY), Path.of(System.getProperty("user.dir")));
    }

    /** Pure variant for tests: explicit property value (may be null) and explicit start directory. */
    public static Path locate(String repoRootProperty, Path startDir) {
        if (repoRootProperty != null && !repoRootProperty.isBlank()) {
            Path fromProperty = Path.of(repoRootProperty);
            if (isServerRoot(fromProperty)) {
                return fromProperty.toAbsolutePath().normalize();
            }
            throw new RepoNotFoundException("resolvr.repo.root was set to '" + repoRootProperty
                    + "' but that directory doesn't look like the Resolvr server repository "
                    + "(expected pom.xml and src/main/resources/application.properties there).");
        }

        Path dir = startDir.toAbsolutePath().normalize();
        while (dir != null) {
            if (isServerRoot(dir)) {
                return dir;
            }
            dir = dir.getParent();
        }

        throw new RepoNotFoundException("Could not locate the Resolvr server repository starting from '"
                + startDir + "' — run this from inside a Resolvr checkout, or launch via bin/resolvr "
                + "so it can tell the CLI where the repository is.");
    }

    /** Non-throwing variant for diagnostics tools (doctor) that want to report absence, not fail. */
    public static Optional<Path> tryLocate() {
        try {
            return Optional.of(locate());
        } catch (RepoNotFoundException e) {
            return Optional.empty();
        }
    }

    private static boolean isServerRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isRegularFile(dir.resolve("src").resolve("main").resolve("resources")
                        .resolve("application.properties"));
    }
}
