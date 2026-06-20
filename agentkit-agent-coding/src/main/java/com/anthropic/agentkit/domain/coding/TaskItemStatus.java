package com.anthropic.agentkit.domain.coding;

/**
 * Lifecycle of a single coding task item within a {@link CodingPlan}.
 */
public enum TaskItemStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    SKIPPED
}
