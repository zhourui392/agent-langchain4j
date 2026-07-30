package com.anthropic.agentkit.domain.diagnosis;

import java.util.List;
import java.util.Objects;

/**
 * Candidate explanation being verified by a diagnosis plan.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record Hypothesis(String id, String statement, double confidence, HypothesisStatus status,
                         List<String> supportingEvidenceIds, List<String> contradictingEvidenceIds) {

    public Hypothesis {
        id = SecretDataPolicy.required(id, "id");
        statement = SecretDataPolicy.required(statement, "statement");
        Objects.requireNonNull(status, "status");
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        supportingEvidenceIds = SecretDataPolicy.sanitizeList(
                Objects.requireNonNull(supportingEvidenceIds, "supportingEvidenceIds"), "evidenceId");
        contradictingEvidenceIds = SecretDataPolicy.sanitizeList(
                Objects.requireNonNull(contradictingEvidenceIds, "contradictingEvidenceIds"), "evidenceId");
    }

    public static Hypothesis open(String id, String statement, double confidence) {
        return new Hypothesis(id, statement, confidence, HypothesisStatus.OPEN, List.of(), List.of());
    }

}
