package com.resolvr.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewThread(
    String threadId,
    String filePath,
    Integer line,
    String commentBody,
    String author,
    String prBranch,
    String owner,
    String repo,
    int prNumber,
    boolean resolved
) {
    public String toPromptContext() {
        return """
            Thread ID : %s
            File      : %s (line %d)
            Comment   : %s
            Branch    : %s
            """.formatted(threadId, filePath, line != null ? line - 1 : 0, commentBody, prBranch);
    }
}
