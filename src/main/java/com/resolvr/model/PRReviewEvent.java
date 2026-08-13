package com.resolvr.model;

import java.time.Instant;

public record PRReviewEvent(
    String owner,
    String repo,
    int prNumber,
    String action,
    Instant receivedAt
) {}
