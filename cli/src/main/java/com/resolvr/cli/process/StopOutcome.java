package com.resolvr.cli.process;

public record StopOutcome(Kind kind, String message) {
    public enum Kind { STOPPED, NOT_RUNNING, FAILED }
}
