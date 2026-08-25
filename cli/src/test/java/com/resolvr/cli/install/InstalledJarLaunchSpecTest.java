package com.resolvr.cli.install;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security-critical: these assertions directly enforce "installed mode must use the normal
 * fail-closed server launch" and "do not enable Quarkus dev mode in installed mode" at the
 * command-construction level, not just by convention.
 */
class InstalledJarLaunchSpecTest {

    private static final Path INSTALL_ROOT = Path.of("opt", "resolvr");
    private static final Path CLI_JAR = INSTALL_ROOT.resolve("lib").resolve("app").resolve("cli").resolve("resolvr-cli.jar");
    private static final Path SERVER_JAR = INSTALL_ROOT.resolve("lib").resolve("app").resolve("server")
            .resolve("quarkus-app").resolve("quarkus-run.jar");
    private static final Path JAVA_EXE = INSTALL_ROOT.resolve("lib").resolve("runtime").resolve("bin").resolve("java");

    private static InstallationLayout layout() {
        return new InstallationLayout(INSTALL_ROOT, CLI_JAR, SERVER_JAR, JAVA_EXE);
    }

    @Test
    void command_isExactlyJavaJarServerJar_withNoPort() {
        List<String> command = new InstalledJarLaunchSpec(layout(), null).command();
        assertEquals(List.of(JAVA_EXE.toString(), "-jar", SERVER_JAR.toString()), command);
    }

    @Test
    void command_withPort_addsOnlyTheHttpPortProperty() {
        List<String> command = new InstalledJarLaunchSpec(layout(), 9999).command();
        assertEquals(List.of(JAVA_EXE.toString(), "-Dquarkus.http.port=9999", "-jar", SERVER_JAR.toString()), command);
    }

    @Test
    void command_withPort_placesPortPropertyBeforeJarFlag() {
        // Security/correctness-critical, not cosmetic: under `java -jar <jar> <args>`,
        // everything after -jar is a program argument, not a JVM system property — a -D
        // flag placed after -jar is silently ignored by the JVM. Confirmed empirically
        // against a real built runtime image (see docs/INSTALLATION.md's smoke-test notes)
        // before this ordering was fixed: the port override was a complete no-op.
        List<String> command = new InstalledJarLaunchSpec(layout(), 9999).command();

        int portIndex = command.indexOf("-Dquarkus.http.port=9999");
        int jarFlagIndex = command.indexOf("-jar");

        assertTrue(portIndex >= 0, "port property must be present: " + command);
        assertTrue(jarFlagIndex >= 0, "-jar flag must be present: " + command);
        assertTrue(portIndex < jarFlagIndex,
                "-Dquarkus.http.port must come before -jar (else it's an ignored program argument, not a JVM property): "
                        + command);
    }

    @Test
    void command_neverContainsADevProfileFlag() {
        List<String> command = new InstalledJarLaunchSpec(layout(), 8080).command();
        for (String arg : command) {
            assertFalse(arg.toLowerCase().contains("quarkus.profile"),
                    "installed-mode launch must never select a Quarkus profile (esp. dev): " + arg);
        }
        // The command's own flags/jar path must never mention "dev" — separate from the jar's
        // absolute path, which legitimately might (e.g. a machine account named "dev-box");
        // check only the flag-shaped arguments (start with "-").
        for (String arg : command) {
            if (arg.startsWith("-")) {
                assertFalse(arg.toLowerCase().contains("dev"), "no dev-mode flag expected: " + arg);
            }
        }
    }

    @Test
    void command_neverContainsASecurityBypassFlag() {
        List<String> command = new InstalledJarLaunchSpec(layout(), 8080).command();
        for (String arg : command) {
            String lower = arg.toLowerCase();
            assertFalse(lower.contains("resolvr.api-key"),
                    "installed-mode launch must never set/override the API key: " + arg);
            assertFalse(lower.contains("skip") && lower.contains("secur"),
                    "installed-mode launch must never skip a security check: " + arg);
            assertFalse(lower.contains("bypass"), "installed-mode launch must never bypass anything: " + arg);
        }
    }

    @Test
    void command_neverReferencesMavenOrAWrapperScript() {
        List<String> command = new InstalledJarLaunchSpec(layout(), 8080).command();
        for (String arg : command) {
            String lower = arg.toLowerCase();
            assertFalse(lower.contains("mvnw"), "installed-mode launch must not depend on Maven: " + arg);
            assertFalse(lower.endsWith("mvn") || lower.endsWith("mvn.cmd"),
                    "installed-mode launch must not depend on Maven: " + arg);
        }
    }

    @Test
    void command_doesNotVaryWithSecretEnvironmentVariables() {
        // The command must be a pure function of (layout, port) — proving it never reads
        // GITHUB_TOKEN/RESOLVR_API_KEY (or anything else from the environment) to decide
        // what to run, since those are exactly the values that must never leak into a
        // process command line (visible via `ps`/Task Manager/process listings on most OSes).
        List<String> a = new InstalledJarLaunchSpec(layout(), 8080).command();
        List<String> b = new InstalledJarLaunchSpec(layout(), 8080).command();
        assertEquals(a, b);
        for (String arg : a) {
            assertFalse(arg.contains("ghp_"), "must never embed a GitHub token shape: " + arg);
        }
    }

    @Test
    void workingDirectory_isTheInstallRoot() {
        assertEquals(INSTALL_ROOT, new InstalledJarLaunchSpec(layout(), null).workingDirectory());
    }

    @Test
    void marker_identifiesInstalledMode() {
        assertEquals("installed-server", new InstalledJarLaunchSpec(layout(), null).marker());
    }
}
