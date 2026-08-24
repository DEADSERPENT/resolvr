package com.resolvr.model;

public record ChangedFile(
        String path,
        int additions,
        int deletions,
        String status,
        String sha
) {
}
