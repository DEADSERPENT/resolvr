package com.resolvr.cli.launch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

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
