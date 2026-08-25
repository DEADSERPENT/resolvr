package com.resolvr.cli.process;

import com.resolvr.cli.testutil.FixedLaunchSpec;
import com.resolvr.cli.testutil.TestJavaProcesses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises real OS process spawn/PID-track/stop/already-running/failure-diagnostics
 * behavior against a genuine child process — {@link com.resolvr.cli.testutil.FakeServerMain},
 * spawned via the exact `java` executable running these tests, not the real Quarkus server
 * (which would require a full build). This is real process management, verified on whatever
 * OS the test suite runs on (the CI matrix covers all three).
 */
class ServerProcessManagerTest {

    @TempDir
    Path tempDir;

    private final List<ServerProcessManager> managersToCleanUp = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // Safety net: force-stop anything a failed assertion left running, so one failing
        // test can't leak an orphaned process into the rest of the suite.
        for (ServerProcessManager manager : managersToCleanUp) {
            try {
                manager.stop();
            } catch (Exception ignored) {
            }
            // On Windows, a just-terminated child process's handle on its redirected log
            // file can take a brief moment to fully release after ProcessHandle reports it
            // as no longer alive — @TempDir's own cleanup then hits a locked-file
            // DirectoryNotEmptyException if it runs first. Draining that race here (our own
            // delete attempt, retried briefly) means the file is already gone by the time
            // @TempDir tries, on every OS.
            deleteWhenUnlocked(manager.logFile());
        }
    }

    private static void deleteWhenUnlocked(Path file) {
        for (int attempt = 1; attempt <= 20; attempt++) {
            try {
                java.nio.file.Files.deleteIfExists(file);
                return;
            } catch (java.io.IOException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private ServerProcessManager newManager(Duration startGrace, Duration stopGrace) {
        PidFile pidFile = new PidFile(tempDir.resolve("resolvr.pid"));
        ServerProcessManager manager = new ServerProcessManager(pidFile, tempDir.resolve("resolvr.log"), startGrace, stopGrace);
        managersToCleanUp.add(manager);
        return manager;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private FixedLaunchSpec fakeServerSpec(int port) {
        return new FixedLaunchSpec(TestJavaProcesses.command(
                "com.resolvr.cli.testutil.FakeServerMain", String.valueOf(port)),
                tempDir, "fake-server", "fake test server on port " + port);
    }

    @Test
    void start_spawnsProcessAndWritesPidFile() throws Exception {
        ServerProcessManager manager = newManager(Duration.ofMillis(1500), Duration.ofSeconds(3));
        StartOutcome outcome = manager.start(fakeServerSpec(freePort()));

        assertEquals(StartOutcome.Kind.STARTED, outcome.kind());
        assertTrue(outcome.pid() > 0);
        assertTrue(ProcessHandle.of(outcome.pid()).map(ProcessHandle::isAlive).orElse(false));

        ProcessStatus status = manager.status();
        assertTrue(status.running());
        assertEquals(outcome.pid(), status.pid());
        assertEquals("fake-server", status.marker());
    }

    @Test
    void start_whenAlreadyRunning_doesNotSpawnASecondProcess() throws Exception {
        ServerProcessManager manager = newManager(Duration.ofMillis(1500), Duration.ofSeconds(3));
        StartOutcome first = manager.start(fakeServerSpec(freePort()));
        assertEquals(StartOutcome.Kind.STARTED, first.kind());

        StartOutcome second = manager.start(fakeServerSpec(freePort()));
        assertEquals(StartOutcome.Kind.ALREADY_RUNNING, second.kind());
        assertEquals(first.pid(), second.pid());
    }

    @Test
    void start_failFastCommand_reportsFailureWithLogTail() throws Exception {
        ServerProcessManager manager = newManager(Duration.ofSeconds(3), Duration.ofSeconds(3));
        FixedLaunchSpec failSpec = new FixedLaunchSpec(TestJavaProcesses.failFastCommand(), tempDir,
                "fail-fast", "deliberately missing main class");

        StartOutcome outcome = manager.start(failSpec);

        assertEquals(StartOutcome.Kind.FAILED, outcome.kind());
        assertFalse(outcome.logTail().isEmpty(), "a fast-failing JVM should leave captured output in the log");
        assertTrue(manager.status().running() == false);
    }

    @Test
    void stop_stopsARunningProcess() throws Exception {
        ServerProcessManager manager = newManager(Duration.ofMillis(1500), Duration.ofSeconds(5));
        StartOutcome started = manager.start(fakeServerSpec(freePort()));
        assertEquals(StartOutcome.Kind.STARTED, started.kind());

        StopOutcome stopped = manager.stop();
        assertEquals(StopOutcome.Kind.STOPPED, stopped.kind());

        // Give the OS a brief moment to finish tearing the process down before asserting.
        long deadline = System.currentTimeMillis() + 5000;
        boolean stillAlive = ProcessHandle.of(started.pid()).map(ProcessHandle::isAlive).orElse(false);
        while (stillAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            stillAlive = ProcessHandle.of(started.pid()).map(ProcessHandle::isAlive).orElse(false);
        }
        assertFalse(stillAlive, "process should be terminated after stop()");
        assertFalse(manager.status().running());
    }

    @Test
    void stop_whenNothingRunning_reportsNotRunning() throws Exception {
        ServerProcessManager manager = newManager(Duration.ofSeconds(1), Duration.ofSeconds(1));
        StopOutcome outcome = manager.stop();
        assertEquals(StopOutcome.Kind.NOT_RUNNING, outcome.kind());
    }

    @Test
    void stop_withStalePidFile_cleansUpWithoutError() throws Exception {
        // A PID file pointing at a PID that (almost certainly) isn't running: pick a fresh
        // process, note its pid, let it exit, then point a PID file at that now-dead pid.
        Process shortLived = new ProcessBuilder(TestJavaProcesses.command("com.resolvr.cli.testutil.FakeServerMain", "0"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        long deadPid = shortLived.pid();
        shortLived.destroyForcibly();
        shortLived.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(shortLived.isAlive());

        PidFile pidFile = new PidFile(tempDir.resolve("stale.pid"));
        pidFile.write(deadPid, "stale", null);
        ServerProcessManager manager = new ServerProcessManager(pidFile, tempDir.resolve("stale.log"),
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        StopOutcome outcome = manager.stop();
        assertEquals(StopOutcome.Kind.NOT_RUNNING, outcome.kind());
        assertTrue(pidFile.read().isEmpty(), "stale PID file should be cleaned up");
    }

    @Test
    void status_reportsNotRunning_whenNoPidFileExists() {
        ServerProcessManager manager = newManager(Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertFalse(manager.status().running());
    }
}
