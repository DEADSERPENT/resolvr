package com.resolvr.cli.install;

import com.resolvr.cli.platform.Architecture;
import com.resolvr.cli.platform.OperatingSystem;
import com.resolvr.cli.platform.Platform;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InstalledStateDirTest {

    @Test
    void windows_usesLocalAppData() {
        Platform windows = new Platform(OperatingSystem.WINDOWS, Architecture.X64);
        Path dir = InstalledStateDir.resolve(windows, Map.of("LOCALAPPDATA", "C:\\Users\\alice\\AppData\\Local"),
                "C:\\Users\\alice");
        assertEquals(Path.of("C:\\Users\\alice\\AppData\\Local", "Resolvr"), dir);
    }

    @Test
    void windows_fallsBackToUserHome_whenLocalAppDataUnset() {
        Platform windows = new Platform(OperatingSystem.WINDOWS, Architecture.X64);
        Path dir = InstalledStateDir.resolve(windows, Map.of(), "C:\\Users\\alice");
        assertEquals(Path.of("C:\\Users\\alice", "Resolvr"), dir);
    }

    @Test
    void macos_usesApplicationSupport() {
        Platform macos = new Platform(OperatingSystem.MACOS, Architecture.ARM64);
        Path dir = InstalledStateDir.resolve(macos, Map.of(), "/Users/alice");
        assertEquals(Path.of("/Users/alice", "Library", "Application Support", "Resolvr"), dir);
    }

    @Test
    void linux_usesXdgStateHome_whenSet() {
        Platform linux = new Platform(OperatingSystem.LINUX, Architecture.X64);
        Path dir = InstalledStateDir.resolve(linux, Map.of("XDG_STATE_HOME", "/home/alice/.state"), "/home/alice");
        assertEquals(Path.of("/home/alice/.state", "resolvr"), dir);
    }

    @Test
    void linux_fallsBackToDotLocalState_whenXdgUnset() {
        Platform linux = new Platform(OperatingSystem.LINUX, Architecture.X64);
        Path dir = InstalledStateDir.resolve(linux, Map.of(), "/home/alice");
        assertEquals(Path.of("/home/alice/.local/state", "resolvr"), dir);
    }

    @Test
    void resolveCurrent_doesNotThrow() {
        assertDoesNotThrow(() -> InstalledStateDir.resolveCurrent(
                com.resolvr.cli.platform.PlatformDetector.detectCurrent()));
    }
}
