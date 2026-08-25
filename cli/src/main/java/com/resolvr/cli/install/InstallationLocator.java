package com.resolvr.cli.install;

import com.resolvr.cli.platform.OperatingSystem;
import com.resolvr.cli.platform.Platform;
import com.resolvr.cli.platform.PlatformDetector;
import com.resolvr.cli.platform.UnsupportedPlatformException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds an installed (jpackage-produced) copy of Resolvr, as the counterpart to
 * {@code RepoLocator} finding a developer's git checkout. The two are mutually exclusive at
 * runtime: an installed native launcher never sits inside a git checkout, and a checkout
 * never sets {@value #APP_PATH_PROPERTY}.
 *
 * <p>Resolution relies on {@code jpackage.app-path} — a system property jpackage's generated
 * native launcher sets automatically to its own executable path. Everything else is
 * arithmetic on that one path, using the jpackage app-image layout convention for each OS:
 *
 * <pre>
 * Windows  &lt;root&gt;\resolvr.exe            &lt;root&gt;\app\...            &lt;root&gt;\runtime\bin\java.exe
 * Linux    &lt;root&gt;/bin/resolvr             &lt;root&gt;/lib/app/...        &lt;root&gt;/lib/runtime/bin/java
 * macOS    &lt;root&gt;/Contents/MacOS/resolvr  &lt;root&gt;/Contents/app/...   &lt;root&gt;/Contents/runtime/Contents/Home/bin/java
 * </pre>
 *
 * The Windows shape above was verified empirically against a real local {@code jpackage
 * --type app-image} build (including confirming {@code jpackage.app-path} is set and that
 * the bundled runtime only contains a real {@code java} executable when the release pipeline
 * passes its own {@code --runtime-image} rather than relying on jpackage's implicit default,
 * which strips it). The Linux/macOS shapes follow jpackage's documented app-image layout but
 * have not been verified against a real build in this environment — see docs/INSTALLATION.md.
 *
 * <p>The {@code --input} directory the release pipeline feeds to jpackage is expected to
 * contain {@code cli/resolvr-cli.jar} and {@code server/quarkus-app/quarkus-run.jar} as its
 * two top-level entries, which is what ends up under {@code app/} (or {@code lib/app/} on
 * Linux, {@code Contents/app/} on macOS) verbatim.
 */
public final class InstallationLocator {

    public static final String APP_PATH_PROPERTY = "jpackage.app-path";

    private InstallationLocator() {
    }

    /** Pure path arithmetic — no filesystem access, always returns a computed layout. Split
     * out from {@link #tryLocate()} so every OS branch is unit-testable without needing a
     * real install of that OS's shape. */
    public static InstallationLayout resolve(Path launcherPath, Platform platform) {
        Path installRoot = installRoot(launcherPath, platform.os());
        Path appDir = appDir(launcherPath, platform.os());
        Path cliJar = appDir.resolve("cli").resolve("resolvr-cli.jar");
        Path serverJar = appDir.resolve("server").resolve("quarkus-app").resolve("quarkus-run.jar");
        Path javaExecutable = javaExecutable(launcherPath, platform.os());
        return new InstallationLayout(installRoot, cliJar, serverJar, javaExecutable);
    }

    /** Convenience: reads the real {@code jpackage.app-path} system property. Returns empty
     * — not an exception — both when the property is absent (not running as an installed
     * launcher, e.g. a developer checkout via bin/resolvr) and when it's present but the
     * computed layout's key files don't actually exist (a partial/corrupt install), since
     * either way there's nothing usable to report as "found." */
    public static Optional<InstallationLayout> tryLocate() {
        String appPath = System.getProperty(APP_PATH_PROPERTY);
        if (appPath == null || appPath.isBlank()) {
            return Optional.empty();
        }
        Platform platform;
        try {
            platform = PlatformDetector.detectCurrent();
        } catch (UnsupportedPlatformException e) {
            return Optional.empty();
        }
        InstallationLayout layout = resolve(Path.of(appPath), platform);
        if (Files.isRegularFile(layout.serverJar()) && Files.isRegularFile(layout.javaExecutable())) {
            return Optional.of(layout);
        }
        return Optional.empty();
    }

    private static Path installRoot(Path launcherPath, OperatingSystem os) {
        Path parent = launcherPath.getParent();
        return switch (os) {
            case WINDOWS -> parent;                             // <root>\resolvr.exe
            case LINUX -> parent.getParent();                   // <root>/bin/resolvr
            case MACOS -> parent.getParent().getParent();       // <root>/Contents/MacOS/resolvr
        };
    }

    private static Path appDir(Path launcherPath, OperatingSystem os) {
        Path parent = launcherPath.getParent();
        return switch (os) {
            case WINDOWS -> parent.resolve("app");
            case LINUX -> parent.getParent().resolve("lib").resolve("app");
            case MACOS -> parent.getParent().resolve("app");    // MacOS/.. == Contents
        };
    }

    private static Path javaExecutable(Path launcherPath, OperatingSystem os) {
        Path parent = launcherPath.getParent();
        return switch (os) {
            case WINDOWS -> parent.resolve("runtime").resolve("bin").resolve("java.exe");
            case LINUX -> parent.getParent().resolve("lib").resolve("runtime").resolve("bin").resolve("java");
            case MACOS -> parent.getParent().resolve("runtime").resolve("Contents").resolve("Home")
                    .resolve("bin").resolve("java");
        };
    }
}
