package com.anthropic.cclc.domain.diagnosis;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Structured plan that constrains diagnosis tool exploration.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record DiagnosisPlan(String problemStatement, List<Hypothesis> hypotheses, List<DiagnosisStep> steps) {

    public DiagnosisPlan {
        if (problemStatement == null || problemStatement.isBlank()) {
            throw new IllegalArgumentException("problemStatement must not be blank");
        }
        hypotheses = List.copyOf(Objects.requireNonNull(hypotheses, "hypotheses"));
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        validateStepHypotheses(hypotheses, steps);
    }

    public boolean isToolAllowed(String toolName) {
        return steps.stream().anyMatch(step -> step.canUseTool(toolName));
    }

    private static void validateStepHypotheses(List<Hypothesis> hypotheses, List<DiagnosisStep> steps) {
        Set<String> hypothesisIds = hypotheses.stream().map(Hypothesis::id).collect(Collectors.toUnmodifiableSet());
        for (DiagnosisStep step : steps) {
            if (!hypothesisIds.contains(step.hypothesisId())) {
                throw new IllegalArgumentException("step hypothesisId not found: " + step.hypothesisId());
            }
        }
    }
}
