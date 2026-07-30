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
        hypothesisId = SecretDataPolicy.required(hypothesisId, "hypothesisId");
        summary = SecretDataPolicy.required(summary, "summary");
        evidenceIds = SecretDataPolicy.sanitizeList(evidenceIds, "evidenceId");
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

}
