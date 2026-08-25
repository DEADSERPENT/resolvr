package com.resolvr.cli.process;

import java.util.List;

public record StartOutcome(Kind kind, long pid, String message, List<String> logTail) {
    public enum Kind { STARTED, ALREADY_RUNNING, FAILED }
}
