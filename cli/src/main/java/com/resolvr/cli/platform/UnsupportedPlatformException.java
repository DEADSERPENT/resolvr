package com.resolvr.cli.platform;

/** Thrown instead of guessing when the running OS/architecture isn't one Resolvr supports. */
public class UnsupportedPlatformException extends RuntimeException {

    public UnsupportedPlatformException(String message) {
        super(message);
    }
}
