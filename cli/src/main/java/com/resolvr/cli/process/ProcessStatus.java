package com.resolvr.cli.process;

public record ProcessStatus(boolean running, Long pid, String marker) {

    public static ProcessStatus notRunning() {
        return new ProcessStatus(false, null, null);
    }
}
