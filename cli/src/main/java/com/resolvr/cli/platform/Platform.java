package com.resolvr.cli.platform;

/** The detected (OS, architecture) pair this CLI is running on. */
public record Platform(OperatingSystem os, Architecture arch) {

    public boolean isWindows() {
        return os == OperatingSystem.WINDOWS;
    }

    @Override
    public String toString() {
        return os.name().toLowerCase(java.util.Locale.ROOT) + "-" + arch.name().toLowerCase(java.util.Locale.ROOT);
    }
}
