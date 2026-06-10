package com.anthropic.cclc.domain.diagnosis;

import java.util.List;

/**
 * Structured diagnosis report produced at the end of a turn.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record DiagnosisReport(String summary, List<RootCauseCandidate> rootCauseCandidates,
                              List<String> keyEvidenceIds, List<String> recommendedActions,
                              double confidence, boolean needHumanCheck) {

    public DiagnosisReport {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        rootCauseCandidates = List.copyOf(rootCauseCandidates);
        keyEvidenceIds = List.copyOf(keyEvidenceIds);
        recommendedActions = List.copyOf(recommendedActions);
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}
