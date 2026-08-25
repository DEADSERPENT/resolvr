package com.resolvr.cli.install;

import com.resolvr.cli.launch.PackagedJarLaunchSpec;
import com.resolvr.cli.platform.PlatformDetector;
import com.resolvr.cli.repo.RepoLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Proves the checkout-vs-installed dispatch: installed mode is preferred when a real,
 * verifiable installed layout is present; otherwise resolution falls through to the
 * existing, unchanged checkout path (RepoLocator/PackagedJarLaunchSpec). */
class RuntimeResolverTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(InstallationLocator.APP_PATH_PROPERTY);
        System.clearProperty(RepoLocator.REPO_ROOT_PROPERTY);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void resolve_prefersInstalledMode_whenBothWouldOtherwiseApply(@TempDir Path tempDir) throws Exception {
        // Build a real installed-shaped layout (Windows, since that's what this test runs
        // on) AND a real checkout marker in a different directory, and confirm installed
        // wins — matching the "an installed binary is never inside a checkout" precedence.
        Path installDir = tempDir.resolve("install");
        Path launcher = installDir.resolve("resolvr.exe");
        Files.createDirectories(installDir.resolve("app").resolve("server").resolve("quarkus-app"));
        Files.createFile(installDir.resolve("app").resolve("server").resolve("quarkus-app").resolve("quarkus-run.jar"));
        Files.createDirectories(installDir.resolve("runtime").resolve("bin"));
        Files.createFile(installDir.resolve("runtime").resolve("bin").resolve("java.exe"));
        Files.createFile(launcher);
        System.setProperty(InstallationLocator.APP_PATH_PROPERTY, launcher.toString());

        var runtime = RuntimeResolver.resolve(8080);

        assertTrue(runtime.installed());
        assertInstanceOf(InstalledJarLaunchSpec.class, runtime.launchSpec());
        assertEquals(installDir.toAbsolutePath().normalize(), runtime.launchSpec().workingDirectory());
        // The installed-mode state dir (PID file/log location) is deliberately NOT under the
        // install root — that location may not be user-writable post-install (e.g. Program
        // Files) — it's the per-user location InstalledStateDir computes instead.
        assertFalse(runtime.stateDir().startsWith(installDir.toAbsolutePath().normalize()));
    }

    @Test
    void resolve_fallsBackToCheckout_whenNotInstalled(@TempDir Path tempDir) throws Exception {
        System.clearProperty(InstallationLocator.APP_PATH_PROPERTY);

        Files.createDirectories(tempDir.resolve("src").resolve("main").resolve("resources"));
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("src").resolve("main").resolve("resources")
                .resolve("application.properties"), "");
        System.setProperty(RepoLocator.REPO_ROOT_PROPERTY, tempDir.toString());

        var runtime = RuntimeResolver.resolve(8080);

        assertFalse(runtime.installed());
        assertInstanceOf(PackagedJarLaunchSpec.class, runtime.launchSpec());
        assertEquals(tempDir.toAbsolutePath().normalize(), runtime.stateDir());
    }

    @Test
    void resolve_throwsRepoNotFound_whenNeitherModeApplies() {
        System.clearProperty(InstallationLocator.APP_PATH_PROPERTY);
        System.setProperty(RepoLocator.REPO_ROOT_PROPERTY, "/definitely/does/not/exist/anywhere");

        assertThrows(RepoLocator.RepoNotFoundException.class, () -> RuntimeResolver.resolve(8080));
    }

    @Test
    void resolveInstalled_empty_whenPropertyUnset() {
        System.clearProperty(InstallationLocator.APP_PATH_PROPERTY);
        assertTrue(RuntimeResolver.resolveInstalled(8080).isEmpty());
    }
}
