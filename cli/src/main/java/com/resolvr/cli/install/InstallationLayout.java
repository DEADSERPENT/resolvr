package com.resolvr.cli.install;

import java.nio.file.Path;

/**
 * Where everything lives inside an installed (jpackage) copy of Resolvr — as opposed to a
 * developer's git checkout, which {@code RepoLocator}/{@code PackagedJarLaunchSpec} continue
 * to handle unchanged. Computed by {@link InstallationLocator}.
 */
public record InstallationLayout(Path installRoot, Path cliJar, Path serverJar, Path javaExecutable) {
}
