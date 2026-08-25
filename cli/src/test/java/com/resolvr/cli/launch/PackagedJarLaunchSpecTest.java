package com.resolvr.cli.launch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackagedJarLaunchSpecTest {

    @Test
    void command_includesJavaJarAndJarPath() {
        Path repoRoot = Path.of("/repo");
        PackagedJarLaunchSpec spec = new PackagedJarLaunchSpec(repoRoot, "/usr/bin/java", null);

        var command = spec.command();
        assertEquals("/usr/bin/java", command.get(0));
        assertEquals("-jar", command.get(1));
        assertEquals(PackagedJarLaunchSpec.jarPath(repoRoot).toString(), command.get(2));
    }

    @Test
    void command_withPort_addsHttpPortSystemProperty() {
        PackagedJarLaunchSpec spec = new PackagedJarLaunchSpec(Path.of("/repo"), "java", 9999);
        assertTrue(spec.command().contains("-Dquarkus.http.port=9999"));
    }

    @Test
    void command_withPort_placesPortPropertyBeforeJarFlag() {
        // Security/correctness-critical, not cosmetic: under `java -jar <jar> <args>`,
        // everything after -jar is a program argument, not a JVM system property — a -D
        // flag placed after -jar is silently ignored by the JVM. Confirmed empirically
        // against a real built runtime image (see docs/INSTALLATION.md's smoke-test notes)
        // before this ordering was fixed: the port override was a complete no-op.
        PackagedJarLaunchSpec spec = new PackagedJarLaunchSpec(Path.of("/repo"), "/usr/bin/java", 9999);
        var command = spec.command();

        int portIndex = command.indexOf("-Dquarkus.http.port=9999");
        int jarFlagIndex = command.indexOf("-jar");

        assertTrue(portIndex >= 0, "port property must be present: " + command);
        assertTrue(jarFlagIndex >= 0, "-jar flag must be present: " + command);
        assertTrue(portIndex < jarFlagIndex,
                "-Dquarkus.http.port must come before -jar (else it's an ignored program argument, not a JVM property): "
                        + command);
        assertEquals(List.of("/usr/bin/java", "-Dquarkus.http.port=9999", "-jar",
                PackagedJarLaunchSpec.jarPath(Path.of("/repo")).toString()), command);
    }

    @Test
    void command_withoutPort_omitsHttpPortSystemProperty() {
        PackagedJarLaunchSpec spec = new PackagedJarLaunchSpec(Path.of("/repo"), "java", null);
        assertTrue(spec.command().stream().noneMatch(arg -> arg.startsWith("-Dquarkus.http.port")));
    }

    @Test
    void jarPath_isUnderTargetQuarkusApp() {
        Path repoRoot = Path.of("/some/repo");
        Path jar = PackagedJarLaunchSpec.jarPath(repoRoot);
        assertEquals(Path.of("/some/repo/target/quarkus-app/quarkus-run.jar"), jar);
    }

    @Test
    void workingDirectory_isRepoRoot() {
        Path repoRoot = Path.of("/repo");
        assertEquals(repoRoot, new PackagedJarLaunchSpec(repoRoot, "java", null).workingDirectory());
    }
}
