package com.resolvr.cli.commands;

import com.resolvr.cli.env.EnvPresence;
import com.resolvr.cli.install.InstallationLocator;
import com.resolvr.cli.launch.LaunchSpec;
import com.resolvr.cli.launch.QuarkusDevLaunchSpec;
import com.resolvr.cli.net.HealthChecker;
import com.resolvr.cli.net.HealthPoller;
import com.resolvr.cli.net.HealthStatus;
import com.resolvr.cli.net.HttpHealthChecker;
import com.resolvr.cli.net.HttpMcpEndpointChecker;
import com.resolvr.cli.net.McpEndpointChecker;
import com.resolvr.cli.net.McpEndpointStatus;
import com.resolvr.cli.net.PortResolver;
import com.resolvr.cli.platform.Platform;
import com.resolvr.cli.platform.PlatformDetector;
import com.resolvr.cli.platform.UnsupportedPlatformException;
import com.resolvr.cli.process.LogFiles;
import com.resolvr.cli.process.ServerProcessManager;
import com.resolvr.cli.process.StartOutcome;
import com.resolvr.cli.repo.RepoLocator;
import com.resolvr.cli.runtime.JavaRuntimeInfo;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * `resolvr dev` — one-command local developer mode: verify prerequisites, launch
 * `mvnw quarkus:dev` (which compiles and hot-reloads on its own — no separate build step
 * needed), wait for health, verify the MCP/SSE endpoint, and report the endpoint plus
 * exactly what auth state the server is running under.
 *
 * Deliberately never writes RESOLVR_API_KEY/GITHUB_TOKEN anywhere: dev mode's "auth is open"
 * behavior already exists in application.properties (%dev.resolvr.api-key=) — this command
 * surfaces that fact clearly rather than generating or storing any credential.
 */
public final class DevCommand implements Command {

    private final HealthChecker healthChecker;
    private final McpEndpointChecker mcpEndpointChecker;
    private final Duration checkTimeout;
    private final Duration overallHealthTimeout;
    private final Duration pollInterval;

    public DevCommand(HealthChecker healthChecker, McpEndpointChecker mcpEndpointChecker,
                       Duration checkTimeout, Duration overallHealthTimeout, Duration pollInterval) {
        this.healthChecker = healthChecker;
        this.mcpEndpointChecker = mcpEndpointChecker;
        this.checkTimeout = checkTimeout;
        this.overallHealthTimeout = overallHealthTimeout;
        this.pollInterval = pollInterval;
    }

    public static DevCommand createDefault() {
        return new DevCommand(new HttpHealthChecker(), new HttpMcpEndpointChecker(),
                Duration.ofSeconds(3), Duration.ofSeconds(120), Duration.ofSeconds(1));
    }

    @Override
    public int run(PrintStream out, String[] args) {
        out.println("Resolvr dev");
        out.println("-----------");

        // Dev mode (quarkus:dev, live-reload, %dev-profile relaxed auth) only makes sense
        // against a source checkout — an installed copy has no source to reload and must
        // never run under the dev profile (see InstalledJarLaunchSpec). Refuse explicitly
        // rather than falling through to a confusing "no repo found" error.
        if (InstallationLocator.tryLocate().isPresent()) {
            out.println("ERROR: `resolvr dev` is not available from an installed copy of Resolvr.");
            out.println("       Dev mode requires a source checkout — see docs/DEVELOPMENT.md.");
            out.println("       Use `resolvr start`/`stop`/`status` instead.");
            return 1;
        }

        // 1. Verify repository/environment
        Path repoRoot;
        try {
            repoRoot = RepoLocator.locate();
        } catch (RepoLocator.RepoNotFoundException e) {
            out.println("ERROR: " + e.getMessage());
            return 1;
        }
        out.println("Repository       : " + repoRoot);

        Platform platform;
        try {
            platform = PlatformDetector.detectCurrent();
        } catch (UnsupportedPlatformException e) {
            out.println("ERROR: " + e.getMessage());
            return 1;
        }
        out.println("Platform         : " + platform);

        // 2. Detect whether the required runtime exists
        JavaRuntimeInfo java = JavaRuntimeInfo.detectCurrent();
        out.println("Java runtime     : " + java.versionString());
        if (!java.meetsMinimum()) {
            out.println("ERROR: Java " + JavaRuntimeInfo.MINIMUM_FEATURE_VERSION
                    + "+ is required (found " + java.versionString() + ").");
            out.println("       Install a JDK " + JavaRuntimeInfo.MINIMUM_FEATURE_VERSION
                    + "+ (e.g. Temurin) and ensure it's first on PATH, or set JAVA_HOME.");
            return 1;
        }

        int port = PortResolver.resolveFromEnvironment();

        // 3+4. Build (handled by quarkus:dev itself) + start
        LaunchSpec spec = new QuarkusDevLaunchSpec(repoRoot, platform, port);
        ServerProcessManager manager = StartCommand.newManager(repoRoot);

        out.println("Launching        : " + spec.description());
        StartOutcome outcome;
        try {
            outcome = manager.start(spec);
        } catch (IOException e) {
            out.println("ERROR: could not launch quarkus:dev: " + e.getMessage());
            return 1;
        }

        if (outcome.kind() == StartOutcome.Kind.FAILED) {
            out.println("ERROR: " + outcome.message());
            printLogTail(out, manager.logFile(), outcome.logTail());
            return 1;
        }
        if (outcome.kind() == StartOutcome.Kind.ALREADY_RUNNING) {
            out.println(outcome.message());
        }

        // 5. Wait for health
        out.println("Waiting for the server to become healthy (this can take a while on first "
                + "run while Maven downloads dependencies)...");
        HealthStatus health;
        try {
            health = HealthPoller.pollUntilHealthy(healthChecker, URI.create("http://localhost:" + port + "/q/health"),
                    checkTimeout, overallHealthTimeout, pollInterval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            health = HealthStatus.UNREACHABLE;
        }

        if (health != HealthStatus.HEALTHY) {
            out.println("ERROR: server did not become healthy within " + overallHealthTimeout.toSeconds()
                    + "s (last status: " + health + ").");
            printLogTail(out, manager.logFile(), LogFiles.tail(manager.logFile(), 30));
            return 1;
        }
        out.println("Server health    : healthy");

        // 6. Verify MCP/SSE endpoint reachability
        McpEndpointStatus mcpStatus = mcpEndpointChecker.check(URI.create("http://localhost:" + port + "/mcp/sse"), checkTimeout);
        out.println("MCP/SSE endpoint : " + mcpStatus);

        // 7. Report the local endpoint
        out.println();
        out.println("Resolvr dev server is up: http://localhost:" + port);
        out.println("MCP endpoint            : http://localhost:" + port + "/mcp/sse");

        // 8. Report authentication/configuration state — never the values themselves
        out.println();
        out.println("Authentication state:");
        out.println("  API key auth   : DISABLED (Quarkus %dev profile)  -  local development only, "
                + "not safe for anything beyond localhost.");
        EnvPresence.Check githubToken = EnvPresence.checkCurrentEnvironment("GITHUB_TOKEN");
        out.println("  GITHUB_TOKEN   : " + (githubToken.present() ? "set" : "not set  -  falls back to "
                + "`gh auth token` if you've run `gh auth login`"));

        return 0;
    }

    private static void printLogTail(PrintStream out, Path logFile, List<String> lines) {
        if (lines.isEmpty()) {
            out.println("       (no log output captured at " + logFile + ")");
            return;
        }
        out.println("       Last log lines (" + logFile + "):");
        for (String line : lines) {
            out.println("         " + line);
        }
    }
}
