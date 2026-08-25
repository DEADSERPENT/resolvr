package com.resolvr.cli.commands;

import com.resolvr.cli.diagnostics.DiagnosticsCollector;
import com.resolvr.cli.diagnostics.DiagnosticsReport;
import com.resolvr.cli.net.HttpHealthChecker;
import com.resolvr.cli.net.HttpMcpEndpointChecker;
import com.resolvr.cli.net.PortResolver;
import com.resolvr.cli.runtime.JavaRuntimeInfo;

import java.io.PrintStream;
import java.time.Duration;

/** `resolvr doctor` — the same checks as `status`, but verbose, with a pass/fail/info marker
 * and an actionable remediation hint under every check that isn't simply OK. Never prints
 * secret values. */
public final class DoctorCommand implements Command {

    private final DiagnosticsCollector collector;

    public DoctorCommand(DiagnosticsCollector collector) {
        this.collector = collector;
    }

    public static DoctorCommand createDefault() {
        int port = PortResolver.resolveFromEnvironment();
        return new DoctorCommand(new DiagnosticsCollector(
                new HttpHealthChecker(), new HttpMcpEndpointChecker(), System.getenv(), port, Duration.ofSeconds(3)));
    }

    @Override
    public int run(PrintStream out, String[] args) {
        DiagnosticsReport r = collector.collect();
        boolean problem = false;

        out.println("Resolvr doctor");
        out.println("--------------");

        if (r.platform().isPresent()) {
            out.println("[OK]   Platform: " + r.platform().get());
        } else {
            problem = true;
            out.println("[FAIL] Platform: " + r.unsupportedPlatformReason().orElse("unsupported"));
            out.println("       Resolvr supports Windows, macOS, and Linux on x64/ARM64.");
        }

        if (r.java().meetsMinimum()) {
            out.println("[OK]   Java runtime: " + r.java().versionString() + " (" + r.java().vendor() + ")");
        } else {
            problem = true;
            out.println("[FAIL] Java runtime: " + r.java().versionString()
                    + "  -  Resolvr requires Java " + JavaRuntimeInfo.MINIMUM_FEATURE_VERSION + "+");
            out.println("       Install a JDK " + JavaRuntimeInfo.MINIMUM_FEATURE_VERSION
                    + "+ (e.g. Temurin) and make sure it's first on PATH, or set JAVA_HOME.");
        }

        if (r.installRoot().isPresent()) {
            out.println("[OK]   Mode: installed (" + r.installRoot().get() + ")");
        } else if (r.repoRoot().isPresent()) {
            out.println("[OK]   Mode: developer checkout (" + r.repoRoot().get() + ")");
        } else {
            problem = true;
            out.println("[FAIL] Neither an installed copy nor a Resolvr checkout was found.");
            out.println("       Run this from inside a Resolvr checkout, launch via bin/resolvr, "
                    + "or run the installed `resolvr` command directly.");
        }

        if (r.portInUse()) {
            out.println("[INFO] Port " + r.port() + ": in use");
        } else {
            out.println("[OK]   Port " + r.port() + ": available");
        }

        switch (r.health()) {
            case HEALTHY -> out.println("[OK]   Server health: healthy");
            case UNHEALTHY -> {
                problem = true;
                out.println("[FAIL] Server health: responding but reporting unhealthy  -  check the server log.");
            }
            case UNREACHABLE -> out.println("[INFO] Server health: not running "
                    + "(try `resolvr start` for the packaged jar, or `resolvr dev` for live-reload dev mode)");
        }

        switch (r.mcpEndpoint()) {
            case REACHABLE -> out.println("[OK]   MCP/SSE endpoint: reachable");
            case REACHABLE_AUTH_REQUIRED -> out.println("[OK]   MCP/SSE endpoint: reachable "
                    + "(requires an Authorization header  -  RESOLVR_API_KEY is set on the server)");
            case UNREACHABLE -> out.println("[INFO] MCP/SSE endpoint: not reachable "
                    + "(expected if the server isn't running)");
        }

        out.println("[INFO] GITHUB_TOKEN: " + (r.githubToken().present() ? "set" : "not set"));
        if (!r.githubToken().present()) {
            out.println("       Set GITHUB_TOKEN, or run `gh auth login`  -  Resolvr falls back to the "
                    + "GitHub CLI's active session if the env var isn't set.");
        }
        out.println("[INFO] RESOLVR_API_KEY: " + (r.apiKey().present() ? "set" : "not set"));
        if (!r.apiKey().present()) {
            out.println("       Fine for local dev. Required for anything reachable beyond localhost  -  "
                    + "the packaged server refuses to start without it outside dev/test.");
        }

        r.serverProcess().ifPresent(ps -> {
            if (ps.running()) {
                out.println("[OK]   Managed process: running (pid " + ps.pid() + ", " + ps.marker() + ")");
            } else {
                out.println("[INFO] Managed process: not running");
            }
        });

        out.println();
        out.println(problem ? "Some checks failed  -  see [FAIL] lines above." : "No blocking problems found.");
        return problem ? 1 : 0;
    }
}
