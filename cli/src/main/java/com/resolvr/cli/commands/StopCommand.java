package com.resolvr.cli.commands;

import com.resolvr.cli.install.ResolvedRuntime;
import com.resolvr.cli.install.RuntimeResolver;
import com.resolvr.cli.process.ServerProcessManager;
import com.resolvr.cli.process.StopOutcome;
import com.resolvr.cli.repo.RepoLocator;

import java.io.IOException;
import java.io.PrintStream;

/** `resolvr stop` — stops the process Resolvr itself started (PID-file tracked, in whichever
 * mode's state dir applies), validating the PID still refers to that same process before
 * touching it, and handles "already stopped" as a clean success rather than an error. */
public final class StopCommand implements Command {

    public static StopCommand createDefault() {
        return new StopCommand();
    }

    @Override
    public int run(PrintStream out, String[] args) {
        ResolvedRuntime runtime;
        try {
            runtime = RuntimeResolver.resolve(null);
        } catch (RepoLocator.RepoNotFoundException e) {
            out.println("ERROR: " + e.getMessage());
            return 1;
        }

        ServerProcessManager manager = StartCommand.newManager(runtime.stateDir());
        StopOutcome outcome;
        try {
            outcome = manager.stop();
        } catch (IOException e) {
            out.println("ERROR: " + e.getMessage());
            return 1;
        }

        out.println(outcome.message());
        return outcome.kind() == StopOutcome.Kind.FAILED ? 1 : 0;
    }
}
