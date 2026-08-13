package com.resolvr.model;

import java.time.Instant;

/**
 * A staged-but-not-yet-committed fix, held in memory until confirm_fix is
 * called. threadId is null when staged via apply_fix (commit only, no
 * associated thread to resolve) and non-null when staged via
 * auto_resolve_all (commit + resolve on confirmation).
 */
public record PendingFix(
        String token,
        String owner,
        String repo,
        String branch,
        String filePath,
        String newContent,
        String commitMessage,
        String threadId,
        Instant createdAt
) {
}
