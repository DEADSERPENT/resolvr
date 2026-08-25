package com.resolvr.cli.install;

import com.resolvr.cli.launch.JavaExecutableLocator;
import com.resolvr.cli.launch.PackagedJarLaunchSpec;
import com.resolvr.cli.platform.PlatformDetector;
import com.resolvr.cli.repo.RepoLocator;

import java.util.Optional;

/**
 * The single place that decides "installed copy or developer checkout" for the commands that
 * need to launch/manage the server (start/stop/status/doctor). Composes the existing,
 * untouched {@code RepoLocator}/{@code PackagedJarLaunchSpec}/{@code JavaExecutableLocator}
 * for the checkout path and the new {@code install} package for the installed path — neither
 * side is modified to know about the other.
 *
 * Installed mode is preferred when detected ({@code jpackage.app-path} present and the
 * bundled files actually exist): an installed binary is never sitting inside a git checkout,
 * so there's no real ambiguity, but preferring it explicitly keeps the precedence obvious.
 */
public final class RuntimeResolver {

    private RuntimeResolver() {
    }

    public static Optional<ResolvedRuntime> resolveInstalled(Integer port) {
        return InstallationLocator.tryLocate().map(layout -> new ResolvedRuntime(
                new InstalledJarLaunchSpec(layout, port),
                InstalledStateDir.resolveCurrent(PlatformDetector.detectCurrent()),
                true));
    }

    /** Throws {@link RepoLocator.RepoNotFoundException} if no checkout is found either —
     * same behavior as today's commands calling {@code RepoLocator.locate()} directly.
     * {@code stateDir} here is the repo root itself, matching what
     * {@code ResolvrPaths.pidFilePath}/{@code logFilePath} already expect as input (they
     * append {@code .resolvr/} themselves) — unchanged from today's behavior. */
    public static ResolvedRuntime resolveCheckout(Integer port) {
        var repoRoot = RepoLocator.locate();
        String javaExecutable = JavaExecutableLocator.locateCurrent();
        return new ResolvedRuntime(
                new PackagedJarLaunchSpec(repoRoot, javaExecutable, port),
                repoRoot,
                false);
    }

    /** Tries installed mode first, falls back to the checkout resolution (which may itself
     * throw {@link RepoLocator.RepoNotFoundException} if neither is found). */
    public static ResolvedRuntime resolve(Integer port) {
        return resolveInstalled(port).orElseGet(() -> resolveCheckout(port));
    }
}
