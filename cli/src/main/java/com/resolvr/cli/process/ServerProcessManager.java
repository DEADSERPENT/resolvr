package com.resolvr.cli.process;

import com.resolvr.cli.launch.LaunchSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OS process lifecycle for the server subprocess — spawn, PID-track, detect already-running,
 * stop (graceful then forceful), and stale/reused-PID detection. Deliberately knows nothing
 * about HTTP health or MCP reachability — that's a separate, app-level concern layered on
 * top by the command classes, so this class stays testable purely in terms of OS processes
 * (verified in tests against a tiny real spawned fixture, not the real Quarkus server).
 */
public final class ServerProcessManager {

    private final PidFile pidFile;
    private final Path logFile;
    private final Duration startGracePeriod;
    private final Duration stopGracePeriod;

    public ServerProcessManager(PidFile pidFile, Path logFile, Duration startGracePeriod, Duration stopGracePeriod) {
        this.pidFile = pidFile;
        this.logFile = logFile;
        this.startGracePeriod = startGracePeriod;
        this.stopGracePeriod = stopGracePeriod;
    }

    public Path logFile() {
        return logFile;
    }

    public StartOutcome start(LaunchSpec spec) throws IOException {
        ProcessStatus existing = status();
        if (existing.running()) {
            return new StartOutcome(StartOutcome.Kind.ALREADY_RUNNING, existing.pid(),
                    "Already running (pid " + existing.pid() + ", " + existing.marker() + ")", List.of());
        }

        if (logFile.getParent() != null) {
            Files.createDirectories(logFile.getParent());
        }

        ProcessBuilder builder = new ProcessBuilder(spec.command())
                .directory(spec.workingDirectory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new StartOutcome(StartOutcome.Kind.FAILED, -1,
                    "Could not launch process: " + e.getMessage(), List.of());
        }

        long pid = process.pid();
        Long startEpochMilli = process.info().startInstant().map(Instant::toEpochMilli).orElse(null);

        boolean exitedDuringGracePeriod;
        try {
            exitedDuringGracePeriod = process.waitFor(startGracePeriod.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitedDuringGracePeriod = false;
        }

        if (exitedDuringGracePeriod) {
            int exitCode = process.exitValue();
            return new StartOutcome(StartOutcome.Kind.FAILED, pid,
                    "Process exited immediately with code " + exitCode, tailLogAfterExit());
        }

        pidFile.write(pid, spec.marker(), startEpochMilli);
        return new StartOutcome(StartOutcome.Kind.STARTED, pid, "Started (pid " + pid + ")", List.of());
    }

    public StopOutcome stop() throws IOException {
        Optional<PidFile.Entry> entryOpt = pidFile.read();
        if (entryOpt.isEmpty()) {
            return new StopOutcome(StopOutcome.Kind.NOT_RUNNING, "No PID file found — nothing to stop.");
        }
        PidFile.Entry entry = entryOpt.get();

        Optional<ProcessHandle> handleOpt = ProcessHandle.of(entry.pid());
        if (handleOpt.isEmpty() || !handleOpt.get().isAlive()) {
            pidFile.delete();
            return new StopOutcome(StopOutcome.Kind.NOT_RUNNING,
                    "Recorded process (pid " + entry.pid() + ") is not running — removed stale PID file.");
        }
        ProcessHandle handle = handleOpt.get();

        if (!matchesRecordedStart(handle, entry)) {
            pidFile.delete();
            return new StopOutcome(StopOutcome.Kind.NOT_RUNNING, "pid " + entry.pid()
                    + " is running but its start time doesn't match what Resolvr recorded — "
                    + "likely PID reuse by an unrelated process. Not stopping it; removed stale PID file.");
        }

        handle.destroy();
        boolean exited = waitForExit(handle, stopGracePeriod);
        if (!exited) {
            handle.destroyForcibly();
            exited = waitForExit(handle, stopGracePeriod);
        }

        pidFile.delete();
        if (exited) {
            return new StopOutcome(StopOutcome.Kind.STOPPED, "Stopped (pid " + entry.pid() + ")");
        }
        return new StopOutcome(StopOutcome.Kind.FAILED,
                "pid " + entry.pid() + " did not exit even after a forceful stop.");
    }

    public ProcessStatus status() {
        Optional<PidFile.Entry> entryOpt = pidFile.read();
        if (entryOpt.isEmpty()) {
            return ProcessStatus.notRunning();
        }
        PidFile.Entry entry = entryOpt.get();
        Optional<ProcessHandle> handleOpt = ProcessHandle.of(entry.pid());
        boolean alive = handleOpt.isPresent() && handleOpt.get().isAlive()
                && matchesRecordedStart(handleOpt.get(), entry);
        if (!alive) {
            return ProcessStatus.notRunning();
        }
        return new ProcessStatus(true, entry.pid(), entry.marker());
    }

    /** Best-effort PID-reuse guard: if the OS reports the running process's actual start
     * time and it doesn't match what we recorded at launch, this PID almost certainly
     * belongs to a different, later process now — not the one Resolvr started. If the OS
     * doesn't expose start time (permissions, platform limitations), fall back to trusting
     * the PID rather than refusing to manage a server we likely did start. */
    private boolean matchesRecordedStart(ProcessHandle handle, PidFile.Entry entry) {
        if (entry.startEpochMilli() == null) {
            return true;
        }
        Optional<Instant> actualStart = handle.info().startInstant();
        if (actualStart.isEmpty()) {
            return true;
        }
        long diffMillis = Math.abs(actualStart.get().toEpochMilli() - entry.startEpochMilli());
        return diffMillis < 5000;
    }

    private boolean waitForExit(ProcessHandle handle, Duration timeout) {
        try {
            handle.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return !handle.isAlive();
        } catch (ExecutionException e) {
            return !handle.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !handle.isAlive();
        }
    }

    /**
     * `process.waitFor()` returning confirms the OS process has terminated, but on some
     * platforms the redirected log file's contents aren't necessarily visible to a fresh
     * read from this process the instant that happens (observed empty-tail reads on Windows
     * immediately after a fast-failing child exited). Retries briefly rather than assuming
     * the first read is authoritative — this only ever costs time on the already-uncommon
     * "process failed within the start grace period" path.
     */
    private List<String> tailLogAfterExit() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            List<String> tail = LogFiles.tail(logFile, 20);
            if (!tail.isEmpty()) {
                return tail;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return LogFiles.tail(logFile, 20);
    }
}
