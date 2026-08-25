package com.resolvr.cli.install;

import com.resolvr.cli.platform.Architecture;
import com.resolvr.cli.platform.OperatingSystem;
import com.resolvr.cli.platform.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InstallationLocator#resolve} is pure path arithmetic — every OS's layout convention
 * is testable without an actual install of that OS's shape. The Windows case was additionally
 * verified against a real local `jpackage --type app-image` build (see class javadoc on
 * InstallationLocator); these assertions encode exactly what that build produced.
 */
class InstallationLocatorTest {

    private static final Platform WINDOWS = new Platform(OperatingSystem.WINDOWS, Architecture.X64);
    private static final Platform LINUX = new Platform(OperatingSystem.LINUX, Architecture.X64);
    private static final Platform MACOS = new Platform(OperatingSystem.MACOS, Architecture.ARM64);

    @AfterEach
    void clearProperty() {
        System.clearProperty(InstallationLocator.APP_PATH_PROPERTY);
    }

    @Test
    void windows_layout_matchesEmpiricallyVerifiedJpackageStructure() {
        // Forward slashes deliberately, even for this Windows-style path: Windows' NIO2
        // provider accepts '/' as an alternate separator (including after a drive letter),
        // and on POSIX '/' is a real separator too — so this exercises the WINDOWS branch's
        // getParent()/resolve() composition logic identically and correctly on all three
        // CI-matrix OSes, rather than being skippable to Windows-only via @EnabledOnOs.
        InstallationLayout layout = InstallationLocator.resolve(Path.of("C:/Program Files/Resolvr/resolvr.exe"), WINDOWS);

        assertEquals(Path.of("C:/Program Files/Resolvr"), layout.installRoot());
        assertEquals(Path.of("C:/Program Files/Resolvr/app/cli/resolvr-cli.jar"), layout.cliJar());
        assertEquals(Path.of("C:/Program Files/Resolvr/app/server/quarkus-app/quarkus-run.jar"), layout.serverJar());
        assertEquals(Path.of("C:/Program Files/Resolvr/runtime/bin/java.exe"), layout.javaExecutable());
    }

    @Test
    void linux_layout_followsDocumentedJpackageConvention() {
        InstallationLayout layout = InstallationLocator.resolve(Path.of("/opt/resolvr/bin/resolvr"), LINUX);

        assertEquals(Path.of("/opt/resolvr"), layout.installRoot());
        assertEquals(Path.of("/opt/resolvr/lib/app/cli/resolvr-cli.jar"), layout.cliJar());
        assertEquals(Path.of("/opt/resolvr/lib/app/server/quarkus-app/quarkus-run.jar"), layout.serverJar());
        assertEquals(Path.of("/opt/resolvr/lib/runtime/bin/java"), layout.javaExecutable());
    }

    @Test
    void macos_layout_followsDocumentedJpackageConvention() {
        InstallationLayout layout = InstallationLocator.resolve(
                Path.of("/Applications/Resolvr.app/Contents/MacOS/resolvr"), MACOS);

        assertEquals(Path.of("/Applications/Resolvr.app"), layout.installRoot());
        assertEquals(Path.of("/Applications/Resolvr.app/Contents/app/cli/resolvr-cli.jar"), layout.cliJar());
        assertEquals(Path.of("/Applications/Resolvr.app/Contents/app/server/quarkus-app/quarkus-run.jar"),
                layout.serverJar());
        assertEquals(Path.of("/Applications/Resolvr.app/Contents/runtime/Contents/Home/bin/java"),
                layout.javaExecutable());
    }

    @Test
    void tryLocate_propertyAbsent_returnsEmpty() {
        System.clearProperty(InstallationLocator.APP_PATH_PROPERTY);
        assertTrue(InstallationLocator.tryLocate().isEmpty());
    }

    @Test
    void tryLocate_propertyPresentButFilesMissing_returnsEmpty() {
        // Points at a syntactically valid but nonexistent install — must not report "found"
        // for a partial/corrupt install with no actual bundled server/runtime.
        System.setProperty(InstallationLocator.APP_PATH_PROPERTY, "C:\\nowhere\\resolvr.exe");
        assertTrue(InstallationLocator.tryLocate().isEmpty());
    }

    @Test
    void tryLocate_realFilesPresent_returnsLayout(@TempDir Path tempDir) throws Exception {
        // Build a minimal real Windows-shaped layout under a temp dir and confirm tryLocate()
        // (using the real platform this test runs on) only succeeds when running on Windows —
        // otherwise assert it stays empty rather than crashing on a mismatched OS shape.
        Path launcher = tempDir.resolve("resolvr.exe");
        Files.createFile(launcher);
        Files.createDirectories(tempDir.resolve("app").resolve("server").resolve("quarkus-app"));
        Files.createFile(tempDir.resolve("app").resolve("server").resolve("quarkus-app").resolve("quarkus-run.jar"));
        Files.createDirectories(tempDir.resolve("runtime").resolve("bin"));
        Files.createFile(tempDir.resolve("runtime").resolve("bin").resolve("java.exe"));

        System.setProperty(InstallationLocator.APP_PATH_PROPERTY, launcher.toString());
        Optional<InstallationLayout> located = InstallationLocator.tryLocate();

        if (com.resolvr.cli.platform.PlatformDetector.detectCurrent().isWindows()) {
            assertTrue(located.isPresent());
            assertEquals(tempDir.toAbsolutePath().normalize(), located.get().installRoot());
        } else {
            // Same temp files, but this OS's layout arithmetic looks in different relative
            // places, so the (nonexistent, wrong-shape) files won't be found — empty is correct.
            assertTrue(located.isEmpty());
        }
    }
}
