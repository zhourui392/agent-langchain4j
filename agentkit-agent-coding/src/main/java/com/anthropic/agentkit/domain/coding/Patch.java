package com.anthropic.agentkit.domain.coding;

import java.util.List;

/**
 * Code change set produced by the coder role and handed to the reviewer.
 *
 * @param summary  human-readable description of what the patch accomplishes
 * @param changes  ordered file mutations; may be empty (e.g. doc-only patches)
 */
public record Patch(String summary, List<FileChange> changes) {

    public Patch {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
