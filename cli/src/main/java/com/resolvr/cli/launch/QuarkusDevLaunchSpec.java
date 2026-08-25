package com.resolvr.cli.launch;

import com.resolvr.cli.platform.Platform;

import java.nio.file.Path;
import java.util.List;

/**
 * `resolvr dev` — runs `mvnw quarkus:dev`, i.e. Quarkus's own dev-mode/live-reload. This is
 * the one OS-branching decision in the launch layer: on Windows the wrapper is
 * {@code mvnw.cmd} (which ProcessBuilder can execute directly by name, same as any other
 * Windows executable/batch file); on macOS/Linux it's the executable {@code mvnw} shell
 * script, invoked directly (it carries its own shebang).
 *
 * Deliberately does NOT run `mvnw package` first — `quarkus:dev` compiles (and hot-reloads
 * on subsequent changes) as part of starting, so a separate build step would just be
 * redundant work.
 *
 * Runs under Quarkus's %dev profile (see application.properties), which is where the
 * project's existing "auth is open for local convenience" behavior already lives — this
 * class doesn't invent or bypass anything; it just launches the same `quarkus:dev` goal a
 * developer would run by hand.
 */
public final class QuarkusDevLaunchSpec implements LaunchSpec {

    private final Path repoRoot;
    private final Platform platform;
    private final Integer port;

    public QuarkusDevLaunchSpec(Path repoRoot, Platform platform, Integer port) {
        this.repoRoot = repoRoot;
        this.platform = platform;
        this.port = port;
    }

    static String wrapperFileName(Platform platform) {
        return platform.isWindows() ? "mvnw.cmd" : "mvnw";
    }

    @Override
    public List<String> command() {
        List<String> cmd = new java.util.ArrayList<>();
        String wrapper = wrapperFileName(platform);
        // On Unix, invoke via the relative "./mvnw" form so it resolves the local wrapper
        // rather than anything unrelated named "mvnw" earlier on PATH.
        cmd.add(platform.isWindows() ? wrapper : "./" + wrapper);
        cmd.add("quarkus:dev");
        if (port != null) {
            cmd.add("-Dquarkus.http.port=" + port);
        }
        cmd.add("-Dquarkus.args=");
        return List.copyOf(cmd);
    }

    @Override
    public Path workingDirectory() {
        return repoRoot;
    }

    @Override
    public String marker() {
        return "quarkus-dev";
    }

    @Override
    public String description() {
        return "quarkus:dev (live-reload dev mode)";
    }
}
