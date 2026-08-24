package com.resolvr.model;

public record PullRequestSummary(
        int number,
        String title,
        String baseBranch,
        String headBranch,
        String headSha,
        String state,
        String htmlUrl
) {
}
