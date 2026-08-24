package com.resolvr.model;

public record CommitInfo(
        String sha,
        String message,
        String author,
        String timestamp
) {
}
