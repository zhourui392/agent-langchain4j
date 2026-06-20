package com.anthropic.agentkit.domain.coding;

/**
 * Single-pass lifecycle of a {@link CodingTask}:
 * PLANNING → CODING → REVIEWING → ACCEPTED | REJECTED.
 */
public enum CodingStatus {
    PLANNING,
    CODING,
    REVIEWING,
    ACCEPTED,
    REJECTED
}
