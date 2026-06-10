package com.anthropic.cclc.domain.diagnosis;

import java.util.List;

public record DiagnosisReportValidationResult(boolean valid, List<String> errors) {

    public DiagnosisReportValidationResult {
        errors = List.copyOf(errors);
    }

    public static DiagnosisReportValidationResult ok() {
        return new DiagnosisReportValidationResult(true, List.of());
    }

    public static DiagnosisReportValidationResult failed(List<String> errors) {
        return new DiagnosisReportValidationResult(false, errors);
    }
}
