package com.resolvr.cli.diagnostics;

import com.resolvr.cli.env.EnvPresence;
import com.resolvr.cli.install.InstallationLayout;
import com.resolvr.cli.install.InstallationLocator;
import com.resolvr.cli.install.InstalledStateDir;
import com.resolvr.cli.net.HealthChecker;
import com.resolvr.cli.net.HealthStatus;
import com.resolvr.cli.net.McpEndpointChecker;
import com.resolvr.cli.net.McpEndpointStatus;
import com.resolvr.cli.net.PortChecker;
import com.resolvr.cli.platform.Platform;
import com.resolvr.cli.platform.PlatformDetector;
import com.resolvr.cli.platform.UnsupportedPlatformException;
import com.resolvr.cli.process.PidFile;
import com.resolvr.cli.process.ProcessStatus;
import com.resolvr.cli.process.ServerProcessManager;
import com.resolvr.cli.repo.RepoLocator;
import com.resolvr.cli.repo.ResolvrPaths;
import com.resolvr.cli.runtime.JavaRuntimeInfo;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Assembles a {@link DiagnosticsReport} from all the individually-testable pieces. Every
 * dependency is passed in (constructor injection), so this class's own orchestration logic
 * (report assembly, "repo not found" handling, "unsupported platform" handling) is testable
 * with fakes, without a real server or a real unsupported-OS machine. */
public final class DiagnosticsCollector {

    private final HealthChecker healthChecker;
    private final McpEndpointChecker mcpEndpointChecker;
    private final Map<String, String> env;
    private final int port;
    private final Duration checkTimeout;

    public DiagnosticsCollector(HealthChecker healthChecker, McpEndpointChecker mcpEndpointChecker,
                                 Map<String, String> env, int port, Duration checkTimeout) {
        this.healthChecker = healthChecker;
        this.mcpEndpointChecker = mcpEndpointChecker;
        this.env = env;
        this.port = port;
        this.checkTimeout = checkTimeout;
    }

    public DiagnosticsReport collect() {
        Optional<Platform> platform;
        Optional<String> unsupportedReason;
        try {
            platform = Optional.of(PlatformDetector.detectCurrent());
            unsupportedReason = Optional.empty();
        } catch (UnsupportedPlatformException e) {
            platform = Optional.empty();
            unsupportedReason = Optional.of(e.getMessage());
        }

        JavaRuntimeInfo java = JavaRuntimeInfo.detectCurrent();

        // Installed mode takes precedence (same as RuntimeResolver): an installed binary is
        // never sitting inside a git checkout, so there's no real ambiguity to resolve.
        Optional<InstallationLayout> installation = InstallationLocator.tryLocate();
        Optional<Path> installRoot = installation.map(InstallationLayout::installRoot);
        Optional<Path> repoRoot = installation.isPresent() ? Optional.empty() : RepoLocator.tryLocate();

        boolean portInUse = PortChecker.isInUse(port);
        HealthStatus health = healthChecker.check(URI.create("http://localhost:" + port + "/q/health"), checkTimeout);
        McpEndpointStatus mcp = mcpEndpointChecker.check(URI.create("http://localhost:" + port + "/mcp/sse"), checkTimeout);

        EnvPresence.Check githubToken = EnvPresence.check(env, "GITHUB_TOKEN");
        EnvPresence.Check apiKey = EnvPresence.check(env, "RESOLVR_API_KEY");

        Optional<Path> stateBaseDir = installation.isPresent()
                ? platform.map(p -> InstalledStateDir.resolve(p, env, System.getProperty("user.home")))
                : repoRoot;
        Optional<ProcessStatus> serverProcess = stateBaseDir.map(base -> {
            PidFile pidFile = new PidFile(ResolvrPaths.pidFilePath(base));
            ServerProcessManager manager = new ServerProcessManager(pidFile,
                    ResolvrPaths.logFilePath(base), Duration.ofSeconds(2), Duration.ofSeconds(5));
            return manager.status();
        });

        return new DiagnosticsReport(platform, unsupportedReason, java, installRoot, repoRoot, port, portInUse,
                health, mcp, githubToken, apiKey, serverProcess);
    }
}
