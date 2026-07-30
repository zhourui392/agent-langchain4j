package com.anthropic.agentkit.domain.diagnosis;

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
        id = SecretDataPolicy.required(id, "id");
        goal = SecretDataPolicy.required(goal, "goal");
        hypothesisId = SecretDataPolicy.required(hypothesisId, "hypothesisId");
        allowedTools = SecretDataPolicy.sanitizeList(
                Objects.requireNonNull(allowedTools, "allowedTools"), "allowedTool");
        if (allowedTools.isEmpty()) {
            throw new IllegalArgumentException("allowedTools must not be empty");
        }
        status = Objects.requireNonNull(status, "status");
        resultSummary = SecretDataPolicy.sanitize(resultSummary);
    }

    public boolean canUseTool(String toolName) {
        return (status == StepStatus.PENDING || status == StepStatus.RUNNING)
                && allowedTools.contains(toolName);
    }

}
