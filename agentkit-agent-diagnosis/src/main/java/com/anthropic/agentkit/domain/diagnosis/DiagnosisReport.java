package com.anthropic.agentkit.domain.diagnosis;

import java.util.List;

/**
 * Structured diagnosis report produced at the end of a turn.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record DiagnosisReport(String summary, List<RootCauseCandidate> rootCauseCandidates,
                              List<String> keyEvidenceIds, List<String> recommendedActions,
                              List<String> missingInformation, double confidence, boolean needHumanCheck) {

    public DiagnosisReport {
        summary = SecretDataPolicy.required(summary, "summary");
        rootCauseCandidates = List.copyOf(rootCauseCandidates);
        keyEvidenceIds = SecretDataPolicy.sanitizeList(keyEvidenceIds, "keyEvidenceId");
        recommendedActions = SecretDataPolicy.sanitizeList(
                recommendedActions, "recommendedAction");
        missingInformation = SecretDataPolicy.sanitizeList(
                missingInformation, "missingInformation");
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    public DiagnosisReport(String summary, List<RootCauseCandidate> rootCauseCandidates,
                           List<String> keyEvidenceIds, List<String> recommendedActions,
                           double confidence, boolean needHumanCheck) {
        this(summary, rootCauseCandidates, keyEvidenceIds, recommendedActions,
                List.of(), confidence, needHumanCheck);
    }
}
