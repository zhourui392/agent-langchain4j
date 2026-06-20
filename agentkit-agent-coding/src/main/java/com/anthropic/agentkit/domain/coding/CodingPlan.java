package com.anthropic.agentkit.domain.coding;

import java.util.List;

/**
 * Structured plan produced by the planner role: a problem statement decomposed
 * into ordered task items.
 *
 * @param problemStatement concise restatement of the requirement
 * @param tasks            ordered decomposition; may be empty for trivial work
 */
public record CodingPlan(String problemStatement, List<TaskItem> tasks) {

    public CodingPlan {
        if (problemStatement == null || problemStatement.isBlank()) {
            throw new IllegalArgumentException("problemStatement must not be blank");
        }
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
