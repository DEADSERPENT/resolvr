package com.resolvr.cli.diagnostics;

import com.resolvr.cli.env.EnvPresence;
import com.resolvr.cli.net.HealthStatus;
import com.resolvr.cli.net.McpEndpointStatus;
import com.resolvr.cli.platform.Platform;
import com.resolvr.cli.process.ProcessStatus;
import com.resolvr.cli.runtime.JavaRuntimeInfo;

import java.nio.file.Path;
import java.util.Optional;

/** Everything `status`/`doctor` need to report — collected once by {@link DiagnosticsCollector}
 * so both commands show a consistent, single-source-of-truth picture; `doctor` just adds
 * remediation hints on top of the same data. Exactly one of {@code platform} /
 * {@code unsupportedPlatformReason} is present. Exactly one of {@code installRoot} /
 * {@code repoRoot} is present when either mode is detected (both empty if neither is —
 * running somewhere that's neither an installed copy nor inside a checkout). */
public record DiagnosticsReport(
        Optional<Platform> platform,
        Optional<String> unsupportedPlatformReason,
        JavaRuntimeInfo java,
        Optional<Path> installRoot,
        Optional<Path> repoRoot,
        int port,
        boolean portInUse,
        HealthStatus health,
        McpEndpointStatus mcpEndpoint,
        EnvPresence.Check githubToken,
        EnvPresence.Check apiKey,
        Optional<ProcessStatus> serverProcess
) {
}
