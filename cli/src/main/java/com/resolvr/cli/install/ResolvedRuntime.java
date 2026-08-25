package com.resolvr.cli.install;

import com.resolvr.cli.launch.LaunchSpec;

import java.nio.file.Path;

/**
 * What a command needs to act, regardless of whether it resolved to an installed copy or a
 * developer checkout: a way to launch the server, and where that mode's PID file/log live.
 */
public record ResolvedRuntime(LaunchSpec launchSpec, Path stateDir, boolean installed) {
}
