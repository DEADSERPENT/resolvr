package com.resolvr.cli.launch;

import java.nio.file.Path;
import java.util.List;

/**
 * `resolvr start` — runs the already-built packaged server jar directly
 * (target/quarkus-app/quarkus-run.jar), the same artifact `mvnw package` produces and the
 * same one docker/Dockerfile ships. Runs in Quarkus's normal (production) launch mode, so
 * StartupSecurityCheck's fail-closed rule applies exactly as it does for any other
 * production start — this command does not relax or bypass it.
 */
public final class PackagedJarLaunchSpec implements LaunchSpec {

    private final Path repoRoot;
    private final String javaExecutable;
    private final Integer port;

    public PackagedJarLaunchSpec(Path repoRoot, String javaExecutable, Integer port) {
        this.repoRoot = repoRoot;
        this.javaExecutable = javaExecutable;
        this.port = port;
    }

    public static Path jarPath(Path repoRoot) {
        return repoRoot.resolve("target").resolve("quarkus-app").resolve("quarkus-run.jar");
    }

    @Override
    public List<String> command() {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaExecutable);
        cmd.add("-jar");
        cmd.add(jarPath(repoRoot).toString());
        if (port != null) {
            cmd.add("-Dquarkus.http.port=" + port);
        }
        return List.copyOf(cmd);
    }

    @Override
    public Path workingDirectory() {
        return repoRoot;
    }

    @Override
    public String marker() {
        return "packaged-jar";
    }

    @Override
    public String description() {
        return "packaged server jar (" + jarPath(repoRoot) + ")";
    }
}
