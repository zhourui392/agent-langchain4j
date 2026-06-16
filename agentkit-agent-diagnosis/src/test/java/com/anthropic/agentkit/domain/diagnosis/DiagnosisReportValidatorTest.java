package com.anthropic.agentkit.domain.diagnosis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisReportValidatorTest {

    private final DiagnosisReportValidator validator = new DiagnosisReportValidator();

    @Test
    void rejectsConfirmedRootCauseBackedOnlyByModelInference() {
        Evidence modelOnly = evidence("E1", EvidenceSource.MODEL_INFERENCE);
        DiagnosisReport report = report(List.of(new RootCauseCandidate(
                "H1", "库存不足", List.of("E1"), 0.8, true)));

        DiagnosisReportValidationResult result = validator.validate(report, List.of(modelOnly));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("non-model evidence"));
    }

    @Test
    void acceptsConfirmedRootCauseWithToolEvidence() {
        Evidence toolEvidence = evidence("E1", EvidenceSource.TOOL_RESULT);
        DiagnosisReport report = report(List.of(new RootCauseCandidate(
                "H1", "库存不足", List.of("E1"), 0.8, true)));

        DiagnosisReportValidationResult result = validator.validate(report, List.of(toolEvidence));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsMissingEvidenceReferences() {
        DiagnosisReport report = report(List.of(new RootCauseCandidate(
                "H1", "库存不足", List.of("missing"), 0.8, true)));

        DiagnosisReportValidationResult result = validator.validate(report, List.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("missing"));
    }

    private static DiagnosisReport report(List<RootCauseCandidate> candidates) {
        return new DiagnosisReport("summary", candidates, List.of(), List.of(), 0.7, false);
    }

    private static Evidence evidence(String id, EvidenceSource source) {
        return new Evidence(id, source, "summary", "raw", "tool", "tu-1", Map.of(), Instant.EPOCH);
    }
}
