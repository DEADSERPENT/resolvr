package com.resolvr.cli.diagnostics;

import com.resolvr.cli.net.HealthStatus;
import com.resolvr.cli.net.McpEndpointStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    void assemblesReport_fromInjectedFakes() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        DiagnosticsCollector collector = new DiagnosticsCollector(
                (url, timeout) -> HealthStatus.HEALTHY,
                (url, timeout) -> McpEndpointStatus.REACHABLE,
                Map.of("GITHUB_TOKEN", "some-token"),
                freePort,
                Duration.ofSeconds(1));

        DiagnosticsReport report = collector.collect();

        assertEquals(HealthStatus.HEALTHY, report.health());
        assertEquals(McpEndpointStatus.REACHABLE, report.mcpEndpoint());
        assertTrue(report.githubToken().present());
        assertFalse(report.apiKey().present());
        assertEquals(freePort, report.port());
        assertFalse(report.portInUse(), "the ephemeral port was closed before use, so it should read as available");
        assertNotNull(report.java());
    }

    @Test
    void repoRoot_present_whenRunFromAServerCheckout() throws Exception {
        Files.createDirectories(tempDir.resolve("src").resolve("main").resolve("resources"));
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("src").resolve("main").resolve("resources").resolve("application.properties"), "");

        System.setProperty(com.resolvr.cli.repo.RepoLocator.REPO_ROOT_PROPERTY, tempDir.toString());
        try {
            DiagnosticsCollector collector = new DiagnosticsCollector(
                    (url, timeout) -> HealthStatus.UNREACHABLE,
                    (url, timeout) -> McpEndpointStatus.UNREACHABLE,
                    Map.of(), 8080, Duration.ofSeconds(1));
            DiagnosticsReport report = collector.collect();
            assertTrue(report.repoRoot().isPresent());
            assertTrue(report.serverProcess().isPresent());
        } finally {
            System.clearProperty(com.resolvr.cli.repo.RepoLocator.REPO_ROOT_PROPERTY);
        }
    }

    @Test
    void repoRoot_absent_whenNotInAServerCheckout() throws Exception {
        System.setProperty(com.resolvr.cli.repo.RepoLocator.REPO_ROOT_PROPERTY, tempDir.toString());
        try {
            DiagnosticsCollector collector = new DiagnosticsCollector(
                    (url, timeout) -> HealthStatus.UNREACHABLE,
                    (url, timeout) -> McpEndpointStatus.UNREACHABLE,
                    Map.of(), 8080, Duration.ofSeconds(1));
            // tempDir has neither pom.xml nor application.properties, so the property points
            // at an invalid root and RepoLocator.tryLocate() must swallow that as "not found".
            DiagnosticsReport report = collector.collect();
            assertTrue(report.repoRoot().isEmpty());
            assertTrue(report.serverProcess().isEmpty());
        } finally {
            System.clearProperty(com.resolvr.cli.repo.RepoLocator.REPO_ROOT_PROPERTY);
        }
    }
}
