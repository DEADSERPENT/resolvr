package com.resolvr.cli.platform;

/** The three OS families Resolvr supports. Deliberately no "other" bucket — an
 * unrecognized OS is a hard {@link UnsupportedPlatformException}, not a silent guess. */
public enum OperatingSystem {
    WINDOWS,
    MACOS,
    LINUX
}
