package com.resolvr.cli.launch;

import com.resolvr.cli.platform.Architecture;
import com.resolvr.cli.platform.OperatingSystem;
import com.resolvr.cli.platform.Platform;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Pure command-construction logic — every OS branch is testable without actually running
 * on that OS, since Platform is just a value passed in. */
class QuarkusDevLaunchSpecTest {

    @Test
    void windows_usesMvnwCmdDirectly() {
        Platform windows = new Platform(OperatingSystem.WINDOWS, Architecture.X64);
        QuarkusDevLaunchSpec spec = new QuarkusDevLaunchSpec(Path.of("/repo"), windows, null);
        assertEquals("mvnw.cmd", spec.command().get(0));
        assertEquals("quarkus:dev", spec.command().get(1));
    }

    @Test
    void macos_usesRelativeMvnw() {
        Platform macos = new Platform(OperatingSystem.MACOS, Architecture.ARM64);
        QuarkusDevLaunchSpec spec = new QuarkusDevLaunchSpec(Path.of("/repo"), macos, null);
        assertEquals("./mvnw", spec.command().get(0));
    }

    @Test
    void linux_usesRelativeMvnw() {
        Platform linux = new Platform(OperatingSystem.LINUX, Architecture.X64);
        QuarkusDevLaunchSpec spec = new QuarkusDevLaunchSpec(Path.of("/repo"), linux, null);
        assertEquals("./mvnw", spec.command().get(0));
    }

    @Test
    void withPort_addsHttpPortSystemProperty() {
        Platform linux = new Platform(OperatingSystem.LINUX, Architecture.X64);
        QuarkusDevLaunchSpec spec = new QuarkusDevLaunchSpec(Path.of("/repo"), linux, 9999);
        assertTrue(spec.command().contains("-Dquarkus.http.port=9999"));
    }

    @Test
    void wrapperFileName_matchesOs() {
        assertEquals("mvnw.cmd", QuarkusDevLaunchSpec.wrapperFileName(new Platform(OperatingSystem.WINDOWS, Architecture.X64)));
        assertEquals("mvnw", QuarkusDevLaunchSpec.wrapperFileName(new Platform(OperatingSystem.MACOS, Architecture.X64)));
        assertEquals("mvnw", QuarkusDevLaunchSpec.wrapperFileName(new Platform(OperatingSystem.LINUX, Architecture.X64)));
    }
}
