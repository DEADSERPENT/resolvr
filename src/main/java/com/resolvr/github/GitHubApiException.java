package com.resolvr.github;

/** Carries the HTTP status code so callers can react to specific GitHub API failures precisely. */
public class GitHubApiException extends RuntimeException {

    private final int statusCode;

    public GitHubApiException(int statusCode, String body) {
        super("GitHub API error " + statusCode + ": " + body);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
