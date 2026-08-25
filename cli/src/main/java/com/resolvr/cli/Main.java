package com.resolvr.cli;

import com.resolvr.cli.commands.Command;
import com.resolvr.cli.commands.DevCommand;
import com.resolvr.cli.commands.DoctorCommand;
import com.resolvr.cli.commands.RestartCommand;
import com.resolvr.cli.commands.StartCommand;
import com.resolvr.cli.commands.StatusCommand;
import com.resolvr.cli.commands.StopCommand;

import java.util.Arrays;
import java.util.Map;

/** Entry point — dispatches {@code args[0]} to a subcommand. Phase 1+2 scope: status, doctor,
 * start, stop, restart, dev. (install/login/connect are later phases, not implemented yet.) */
public final class Main {

    private static final Map<String, java.util.function.Supplier<Command>> COMMANDS = Map.of(
            "status", StatusCommand::createDefault,
            "doctor", DoctorCommand::createDefault,
            "start", StartCommand::createDefault,
            "stop", StopCommand::createDefault,
            "restart", RestartCommand::createDefault,
            "dev", DevCommand::createDefault
    );

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    static int run(String[] args, java.io.PrintStream out) {
        if (args.length == 0) {
            printUsage(out);
            return 1;
        }
        String name = args[0];
        var factory = COMMANDS.get(name);
        if (factory == null) {
            out.println("Unknown command: " + name);
            printUsage(out);
            return 1;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return factory.get().run(out, rest);
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("Usage: resolvr <command>");
        out.println();
        out.println("Commands:");
        out.println("  status    Show OS/Java/server/config diagnostics");
        out.println("  doctor    Detailed diagnostics with remediation hints");
        out.println("  start     Start the packaged server as a background process");
        out.println("  stop      Stop the server process Resolvr started");
        out.println("  restart   Stop, then start");
        out.println("  dev       One-command local dev mode (quarkus:dev, live-reload)");
    }

    private Main() {
    }
}
