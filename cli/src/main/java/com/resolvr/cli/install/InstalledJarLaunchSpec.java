package com.resolvr.cli.install;

import com.resolvr.cli.launch.LaunchSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The installed-mode counterpart to {@code PackagedJarLaunchSpec}: runs the bundled server
 * jar with the bundled {@code java} executable — no Maven, no git, no source checkout
 * involved anywhere in the command it builds.
 *
 * <p>Security-relevant by design: this command is <strong>exactly</strong>
 * {@code <bundled-java> [-Dquarkus.http.port=N] -jar <bundled-quarkus-run.jar>} and nothing
 * else — the port flag is placed before {@code -jar} deliberately, since a {@code -D} flag
 * placed after it would be swallowed as a program argument instead of a JVM system property
 * (see {@link #command()}). It never adds {@code -Dquarkus.profile=dev}, never adds {@code -Dresolvr.api-key=...}
 * or any other flag that could relax {@code StartupSecurityCheck}'s fail-closed behavior, and
 * there is no code path anywhere in this class that reads or forwards
 * {@code RESOLVR_API_KEY}/{@code GITHUB_TOKEN} values. The server launched this way runs in
 * normal (production) Quarkus launch mode exactly as {@code PackagedJarLaunchSpec} does for a
 * developer checkout — installed mode is not a separate, weaker security posture.
 */
public final class InstalledJarLaunchSpec implements LaunchSpec {

    private final InstallationLayout layout;
    private final Integer port;

    public InstalledJarLaunchSpec(InstallationLayout layout, Integer port) {
        this.layout = layout;
        this.port = port;
    }

    @Override
    public List<String> command() {
        // -Dquarkus.http.port must come BEFORE -jar: once `java -jar <jar>` is on the
        // command line, everything after the jar path is a program argument, not a JVM
        // system property — a -D flag placed after -jar is silently ignored.
        List<String> cmd = new ArrayList<>();
        cmd.add(layout.javaExecutable().toString());
        if (port != null) {
            cmd.add("-Dquarkus.http.port=" + port);
        }
        cmd.add("-jar");
        cmd.add(layout.serverJar().toString());
        return List.copyOf(cmd);
    }

    @Override
    public Path workingDirectory() {
        return layout.installRoot();
    }

    @Override
    public String marker() {
        return "installed-server";
    }

    @Override
    public String description() {
        return "installed server (" + layout.serverJar() + ")";
    }
}
