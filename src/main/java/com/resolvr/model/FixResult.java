package com.resolvr.model;

public record FixResult(
    String threadId,
    String filePath,
    String commitSha,
    boolean resolved,
    String error
) {
    public static FixResult success(String threadId, String filePath, String commitSha) {
        return new FixResult(threadId, filePath, commitSha, true, null);
    }

    public static FixResult failure(String threadId, String filePath, String error) {
        return new FixResult(threadId, filePath, null, false, error);
    }
}
