package com.anthropic.agentkit.domain.diagnosis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enforces evidence invariants for structured diagnosis reports.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class DiagnosisReportValidator {

    public DiagnosisReportValidationResult validate(DiagnosisReport report, List<Evidence> evidence) {
        Map<String, Evidence> evidenceById = evidence.stream()
                .collect(Collectors.toMap(Evidence::id, Function.identity()));
        List<String> errors = new ArrayList<>();
        for (RootCauseCandidate candidate : report.rootCauseCandidates()) {
            validateEvidenceReferences(candidate, evidenceById, errors);
            validateConfirmedCandidate(candidate, evidenceById, errors);
        }
        return errors.isEmpty()
                ? DiagnosisReportValidationResult.ok()
                : DiagnosisReportValidationResult.failed(errors);
    }

    private static void validateEvidenceReferences(RootCauseCandidate candidate,
                                                   Map<String, Evidence> evidenceById,
                                                   List<String> errors) {
        for (String evidenceId : candidate.evidenceIds()) {
            if (!evidenceById.containsKey(evidenceId)) {
                errors.add("missing evidence reference: " + evidenceId);
            }
        }
    }

    private static void validateConfirmedCandidate(RootCauseCandidate candidate,
                                                   Map<String, Evidence> evidenceById,
                                                   List<String> errors) {
        if (!candidate.confirmed()) {
            return;
        }
        boolean hasNonModelEvidence = candidate.evidenceIds().stream()
                .map(evidenceById::get)
                .anyMatch(evidence -> evidence != null && evidence.source() != EvidenceSource.MODEL_INFERENCE);
        if (!hasNonModelEvidence) {
            errors.add("confirmed root cause requires at least one non-model evidence");
        }
    }
}
