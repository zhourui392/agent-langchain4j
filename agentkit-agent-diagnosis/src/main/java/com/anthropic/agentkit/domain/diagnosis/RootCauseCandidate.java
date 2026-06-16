package com.anthropic.agentkit.domain.diagnosis;

import java.util.List;

/**
 * Candidate root cause and its supporting evidence references.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record RootCauseCandidate(String hypothesisId, String summary, List<String> evidenceIds,
                                 double confidence, boolean confirmed) {

    public RootCauseCandidate {
        requireText(hypothesisId, "hypothesisId");
        requireText(summary, "summary");
        evidenceIds = List.copyOf(evidenceIds);
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
