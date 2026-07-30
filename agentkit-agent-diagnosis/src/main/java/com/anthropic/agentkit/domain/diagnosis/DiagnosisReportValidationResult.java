package com.anthropic.agentkit.domain.diagnosis;

import java.util.List;

/**
 * Validation outcome whose public error descriptions are safe to log or return.
 *
 * @author alex
 */
public record DiagnosisReportValidationResult(boolean valid, List<String> errors) {

    public DiagnosisReportValidationResult {
        errors = SecretDataPolicy.sanitizeList(errors, "error");
    }

    public static DiagnosisReportValidationResult ok() {
        return new DiagnosisReportValidationResult(true, List.of());
    }

    public static DiagnosisReportValidationResult failed(List<String> errors) {
        return new DiagnosisReportValidationResult(false, errors);
    }
}
