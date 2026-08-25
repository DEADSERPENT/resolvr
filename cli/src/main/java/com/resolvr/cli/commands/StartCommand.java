package com.resolvr.cli.commands;

import com.resolvr.cli.install.ResolvedRuntime;
import com.resolvr.cli.install.RuntimeResolver;
import com.resolvr.cli.launch.LaunchSpec;
import com.resolvr.cli.launch.PackagedJarLaunchSpec;
import com.resolvr.cli.net.HealthChecker;
import com.resolvr.cli.net.HealthPoller;
import com.resolvr.cli.net.HealthStatus;
import com.resolvr.cli.net.HttpHealthChecker;
import com.resolvr.cli.net.PortResolver;
import com.resolvr.cli.process.LogFiles;
import com.resolvr.cli.process.PidFile;
import com.resolvr.cli.process.ServerProcessManager;
import com.resolvr.cli.process.StartOutcome;
import com.resolvr.cli.repo.RepoLocator;
import com.resolvr.cli.repo.ResolvrPaths;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** `resolvr start` — runs the server as a background subprocess (the bundled server if
 * installed, or the packaged jar in a developer checkout if not), waits for /q/health, and
 * reports success or a clean diagnostic. Does not build anything (that's `resolvr dev`'s job,
 * checkout-only) and does not touch RESOLVR_API_KEY/GITHUB_TOKEN — it runs the server exactly
 * as configured in the environment, fail-closed behavior included, in both modes. */
public final class StartCommand implements Command {

    private final HealthChecker healthChecker;
    private final Duration healthCheckTimeout;
    private final Duration overallHealthTimeout;
    private final Duration pollInterval;

    public StartCommand(HealthChecker healthChecker, Duration healthCheckTimeout,
                         Duration overallHealthTimeout, Duration pollInterval) {
        this.healthChecker = healthChecker;
        this.healthCheckTimeout = healthCheckTimeout;
        this.overallHealthTimeout = overallHealthTimeout;
        this.pollInterval = pollInterval;
    }

    public static StartCommand createDefault() {
        return new StartCommand(new HttpHealthChecker(), Duration.ofSeconds(3),
                Duration.ofSeconds(60), Duration.ofMillis(500));
    }

    @Override
    public int run(PrintStream out, String[] args) {
        int port = PortResolver.resolveFromEnvironment();

        ResolvedRuntime runtime;
        try {
            runtime = RuntimeResolver.resolve(port);
        } catch (RepoLocator.RepoNotFoundException e) {
            out.println("ERROR: " + e.getMessage());
            return 1;
        }

        if (!runtime.installed()) {
            // Installed mode already guarantees the bundled jar exists (verified by
            // InstallationLocator.tryLocate()) — this check only applies to a checkout,
            // where stateDir() is the repo root (see RuntimeResolver.resolveCheckout).
            Path jar = PackagedJarLaunchSpec.jarPath(runtime.stateDir());
            if (!Files.isRegularFile(jar)) {
                out.println("ERROR: packaged server jar not found at " + jar);
                out.println("       Build it first (from " + runtime.stateDir() + "): ./mvnw package -DskipTests");
                out.println("       Or use `resolvr dev` for a live-reload dev server that builds automatically.");
                return 1;
            }
        }

        LaunchSpec spec = runtime.launchSpec();
        ServerProcessManager manager = newManager(runtime.stateDir());
        StartOutcome outcome;
        try {
            outcome = manager.start(spec);
        } catch (IOException e) {
            out.println("ERROR: could not start the server process: " + e.getMessage());
            return 1;
        }

        switch (outcome.kind()) {
            case ALREADY_RUNNING -> {
                out.println(outcome.message());
                return 0;
            }
            case FAILED -> {
                out.println("ERROR: " + outcome.message());
                printLogTail(out, manager.logFile(), outcome.logTail());
                return 1;
            }
            case STARTED -> out.println("Starting " + spec.description() + " (pid " + outcome.pid() + ")...");
        }

        HealthStatus status;
        try {
            status = HealthPoller.pollUntilHealthy(healthChecker, URI.create("http://localhost:" + port + "/q/health"),
                    healthCheckTimeout, overallHealthTimeout, pollInterval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = HealthStatus.UNREACHABLE;
        }

        if (status == HealthStatus.HEALTHY) {
            out.println("Resolvr is running: http://localhost:" + port);
            out.println("MCP endpoint      : http://localhost:" + port + "/mcp/sse");
            return 0;
        }

        out.println("ERROR: server process started but did not become healthy within "
                + overallHealthTimeout.toSeconds() + "s (last status: " + status + ").");
        printLogTail(out, manager.logFile(), LogFiles.tail(manager.logFile(), 20));
        return 1;
    }

    /** Shared by StopCommand/DevCommand too — takes any base dir (a repo root, or the
     * installed-mode state dir); ResolvrPaths appends .resolvr/ under it either way. */
    static ServerProcessManager newManager(Path baseDir) {
        PidFile pidFile = new PidFile(ResolvrPaths.pidFilePath(baseDir));
        return new ServerProcessManager(pidFile, ResolvrPaths.logFilePath(baseDir),
                Duration.ofSeconds(3), Duration.ofSeconds(10));
    }

    private static void printLogTail(PrintStream out, Path logFile, java.util.List<String> lines) {
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
