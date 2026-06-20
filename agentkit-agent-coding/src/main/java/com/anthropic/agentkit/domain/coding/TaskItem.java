package com.anthropic.agentkit.domain.coding;

import java.util.List;

/**
 * One atomic unit of work identified by the planner.
 *
 * @param id        stable identifier referenced by the coder/reviewer
 * @param goal      human-readable description of the work
 * @param files     file paths the item is expected to touch (may be empty)
 * @param status    current lifecycle state
 */
public record TaskItem(String id, String goal, List<String> files, TaskItemStatus status) {

    public TaskItem {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        files = files == null ? List.of() : List.copyOf(files);
        status = status == null ? TaskItemStatus.PENDING : status;
    }
}
