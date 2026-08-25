package com.resolvr.cli.commands;

import java.io.PrintStream;

/** One CLI subcommand. Returns a process exit code (0 = success). */
public interface Command {
    int run(PrintStream out, String[] args);
}
