package com.resolvr.cli.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Pure detection logic — every OS/arch combination is testable on any machine since
 * detect() takes the raw strings explicitly rather than reading real system properties. */
class PlatformDetectorTest {

    @ParameterizedTest
    @CsvSource({
            "Windows 10, amd64, WINDOWS, X64",
            "Windows 11, amd64, WINDOWS, X64",
            "Windows Server 2022, aarch64, WINDOWS, ARM64",
            "Mac OS X, x86_64, MACOS, X64",
            "Mac OS X, aarch64, MACOS, ARM64",
            "Linux, amd64, LINUX, X64",
            "Linux, x86_64, LINUX, X64",
            "Linux, aarch64, LINUX, ARM64",
            "Linux, arm64, LINUX, ARM64",
    })
    void detectsKnownCombinations(String osName, String osArch, OperatingSystem expectedOs, Architecture expectedArch) {
        Platform platform = PlatformDetector.detect(osName, osArch);
        assertEquals(expectedOs, platform.os());
        assertEquals(expectedArch, platform.arch());
    }

    @Test
    void unsupportedOs_throwsCleanException() {
        UnsupportedPlatformException ex = assertThrows(UnsupportedPlatformException.class,
                () -> PlatformDetector.detect("SunOS", "sparc"));
        assertTrue(ex.getMessage().contains("SunOS"));
    }

    @Test
    void unsupportedArch_throwsCleanException() {
        UnsupportedPlatformException ex = assertThrows(UnsupportedPlatformException.class,
                () -> PlatformDetector.detect("Linux", "sparc"));
        assertTrue(ex.getMessage().contains("sparc"));
    }

    @Test
    void blankOsName_throwsCleanException() {
        assertThrows(UnsupportedPlatformException.class, () -> PlatformDetector.detect("", "amd64"));
    }

    @Test
    void nullOsName_throwsCleanException() {
        assertThrows(UnsupportedPlatformException.class, () -> PlatformDetector.detect(null, "amd64"));
    }

    @Test
    void detectCurrent_worksOnWhicheverMachineIsRunningTheTests() {
        // Not asserting a specific OS/arch — just that the real system properties this
        // machine reports are ones PlatformDetector can classify without throwing.
        Platform platform = PlatformDetector.detectCurrent();
        assertNotNull(platform.os());
        assertNotNull(platform.arch());
    }

    @Test
    void isWindows_reflectsOs() {
        assertTrue(PlatformDetector.detect("Windows 11", "amd64").isWindows());
        assertFalse(PlatformDetector.detect("Linux", "amd64").isWindows());
        assertFalse(PlatformDetector.detect("Mac OS X", "amd64").isWindows());
    }
}
