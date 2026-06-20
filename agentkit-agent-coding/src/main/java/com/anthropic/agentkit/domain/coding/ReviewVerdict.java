package com.anthropic.agentkit.domain.coding;

import java.util.List;

/**
 * Structured verdict produced by the reviewer role.
 *
 * @param decision the terminal {@link Verdict}
 * @param summary  reviewer's free-form rationale
 * @param issues   concrete problems cited; empty when accepted
 */
public record ReviewVerdict(Verdict decision, String summary, List<String> issues) {

    public ReviewVerdict {
        if (decision == null) {
            throw new NullPointerException("decision must not be null");
        }
        if (summary == null) {
            throw new NullPointerException("summary must not be null");
        }
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
