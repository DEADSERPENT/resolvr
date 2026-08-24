package com.resolvr.model;

public record PullRequestMetadata(
        int number,
        String title,
        String body,
        String state,
        String author,
        String baseBranch,
        String headBranch,
        String headSha,
        String createdAt,
        String updatedAt,
        String htmlUrl
) {
}
