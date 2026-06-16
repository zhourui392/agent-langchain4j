package com.anthropic.agentkit.application.diagnosis;

import java.util.Objects;

/**
 * Structured input for a diagnosis sub-task.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record DiagnosisTaskRequest(String taskType,
                                   String hypothesisId,
                                   String goal,
                                   String scopeSummary) {

    public DiagnosisTaskRequest {
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(hypothesisId, "hypothesisId");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(scopeSummary, "scopeSummary");
    }
}
