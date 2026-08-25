package com.resolvr.cli.commands;

import com.resolvr.cli.diagnostics.DiagnosticsCollector;
import com.resolvr.cli.diagnostics.DiagnosticsReport;
import com.resolvr.cli.net.HealthStatus;
import com.resolvr.cli.net.HttpHealthChecker;
import com.resolvr.cli.net.HttpMcpEndpointChecker;
import com.resolvr.cli.net.PortResolver;
import com.resolvr.cli.runtime.JavaRuntimeInfo;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;

/** `resolvr status` — a concise, single-pass snapshot. Never prints secret values, only
 * whether GITHUB_TOKEN/RESOLVR_API_KEY are set. */
public final class StatusCommand implements Command {

    private final DiagnosticsCollector collector;

    public StatusCommand(DiagnosticsCollector collector) {
        this.collector = collector;
    }

    public static StatusCommand createDefault() {
        int port = PortResolver.resolveFromEnvironment();
        return new StatusCommand(new DiagnosticsCollector(
                new HttpHealthChecker(), new HttpMcpEndpointChecker(), System.getenv(), port, Duration.ofSeconds(3)));
    }

    @Override
    public int run(PrintStream out, String[] args) {
        DiagnosticsReport r = collector.collect();

        out.println("Resolvr status");
        out.println("--------------");
        out.println("Platform         : " + r.platform().map(Object::toString)
                .orElseGet(() -> "UNSUPPORTED (" + r.unsupportedPlatformReason().orElse("unknown") + ")"));
        out.println("Java runtime     : " + r.java().versionString()
                + (r.java().meetsMinimum() ? "" : " - below required " + JavaRuntimeInfo.MINIMUM_FEATURE_VERSION + "+"));
        out.println("Repository       : " + r.repoRoot().map(Path::toString).orElse("not found"));
        out.println("Port " + r.port() + "         : " + (r.portInUse() ? "in use" : "available"));
        out.println("Server health    : " + r.health());
        out.println("MCP/SSE endpoint : " + r.mcpEndpoint());
        out.println("GITHUB_TOKEN     : " + (r.githubToken().present() ? "set" : "not set"));
        out.println("RESOLVR_API_KEY  : " + (r.apiKey().present() ? "set" : "not set"));
        r.serverProcess().ifPresent(ps -> out.println("Managed process  : "
                + (ps.running() ? "running (pid " + ps.pid() + ", " + ps.marker() + ")" : "not running")));

        return r.health() == HealthStatus.HEALTHY ? 0 : 1;
    }
}
