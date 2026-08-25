package com.resolvr.cli.platform;

import java.util.Locale;

/**
 * Pure OS/architecture detection. {@link #detect(String, String)} takes the raw
 * {@code os.name}/{@code os.arch} strings explicitly so it's fully unit-testable for every
 * platform combination without needing to actually run on that platform; {@link #detectCurrent()}
 * is the convenience entry point that reads the real system properties.
 */
public final class PlatformDetector {

    private PlatformDetector() {
    }

    public static Platform detectCurrent() {
        return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    public static Platform detect(String osName, String osArch) {
        return new Platform(detectOs(osName), detectArch(osArch));
    }

    private static OperatingSystem detectOs(String osName) {
        if (osName == null || osName.isBlank()) {
            throw new UnsupportedPlatformException("Could not determine the operating system "
                    + "(os.name was blank/unset).");
        }
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return OperatingSystem.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return OperatingSystem.MACOS;
        }
        if (name.contains("linuz")) {
            return OperatingSystem.LINUX;
        }
        throw new UnsupportedPlatformException("Unsupported operating system: '" + osName
                + "'. Resolvr supports Windows, macOS, and Linux.");
    }

    private static Architecture detectArch(String osArch) {
        if (osArch == null || osArch.isBlank()) {
            throw new UnsupportedPlatformException("Could not determine the CPU architecture "
                    + "(os.arch was blank/unset).");
        }
        String arch = osArch.toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) {
            return Architecture.X64;
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return Architecture.ARM64;
        }
        throw new UnsupportedPlatformException("Unsupported CPU architecture: '" + osArch
                + "'. Resolvr supports x64 and ARM64.");
    }
}
