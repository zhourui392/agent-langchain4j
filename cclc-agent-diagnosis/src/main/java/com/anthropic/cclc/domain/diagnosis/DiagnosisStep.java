package com.anthropic.cclc.domain.diagnosis;

import java.util.List;
import java.util.Objects;

/**
 * A planned evidence-gathering step for one hypothesis.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record DiagnosisStep(String id, String goal, String hypothesisId, List<String> allowedTools,
                            StepStatus status, String resultSummary) {

    public DiagnosisStep {
        requireText(id, "id");
        requireText(goal, "goal");
        requireText(hypothesisId, "hypothesisId");
        allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        if (allowedTools.isEmpty()) {
            throw new IllegalArgumentException("allowedTools must not be empty");
        }
        status = Objects.requireNonNull(status, "status");
        resultSummary = resultSummary == null ? "" : resultSummary;
    }

    public boolean canUseTool(String toolName) {
        return (status == StepStatus.PENDING || status == StepStatus.RUNNING)
                && allowedTools.contains(toolName);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
