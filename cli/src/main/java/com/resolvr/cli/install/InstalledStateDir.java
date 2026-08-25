package com.resolvr.cli.install;

import com.resolvr.cli.platform.Platform;

import java.nio.file.Path;
import java.util.Map;

/**
 * Where the CLI keeps its PID file/log when running in installed mode. Unlike the developer
 * checkout path (state lives under {@code <repoRoot>/.resolvr/}, writable by whoever owns
 * the checkout), an installed copy commonly lives somewhere requiring elevated privileges to
 * write into (e.g. {@code C:\Program Files\...} on Windows), so installed-mode state must
 * live in a per-user, always-writable location instead — the install directory itself is
 * never written to after setup.
 */
public final class InstalledStateDir {

    private InstalledStateDir() {
    }

    public static Path resolve(Platform platform, Map<String, String> env, String userHome) {
        return switch (platform.os()) {
            case WINDOWS -> Path.of(env.getOrDefault("LOCALAPPDATA", userHome), "Resolvr");
            case MACOS -> Path.of(userHome, "Library", "Application Support", "Resolvr");
            case LINUX -> Path.of(env.getOrDefault("XDG_STATE_HOME", userHome + "/.local/state"), "resolvr");
        };
    }

    public static Path resolveCurrent(Platform platform) {
        return resolve(platform, System.getenv(), System.getProperty("user.home"));
    }
}
