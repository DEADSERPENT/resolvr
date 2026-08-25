package com.resolvr.cli.commands;

import java.io.PrintStream;

/** `resolvr restart` — stop (tolerating "already stopped"), then start. Simple composition;
 * no independent logic of its own to keep the two commands' behavior consistent. */
public final class RestartCommand implements Command {

    private final StopCommand stop;
    private final StartCommand start;

    public RestartCommand(StopCommand stop, StartCommand start) {
        this.stop = stop;
        this.start = start;
    }

    public static RestartCommand createDefault() {
        return new RestartCommand(StopCommand.createDefault(), StartCommand.createDefault());
    }

    @Override
    public int run(PrintStream out, String[] args) {
        int stopCode = stop.run(out, args);
        if (stopCode != 0) {
            out.println("WARNING: stop reported a problem  -  attempting to start anyway.");
        }
        return start.run(out, args);
    }
}
