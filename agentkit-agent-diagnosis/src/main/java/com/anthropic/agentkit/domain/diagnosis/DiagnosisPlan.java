package com.anthropic.agentkit.domain.diagnosis;

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
public record DiagnosisPlan(String problemStatement, List<Hypothesis> hypotheses, List<DiagnosisStep> steps,
                            List<String> missingInputs, DiagnosisScope scope,
                            List<DiagnosisBlocker> blockers, long capabilityGeneration,
                            long resourceGeneration) {

    public DiagnosisPlan {
        problemStatement = SecretDataPolicy.required(problemStatement, "problemStatement");
        hypotheses = List.copyOf(Objects.requireNonNull(hypotheses, "hypotheses"));
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        missingInputs = SecretDataPolicy.sanitizeList(missingInputs, "missingInput");
        scope = scope == null ? DiagnosisScope.unknown() : scope;
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
        validateStepHypotheses(hypotheses, steps);
    }

    public DiagnosisPlan(String problemStatement, List<Hypothesis> hypotheses, List<DiagnosisStep> steps,
                         List<String> missingInputs, DiagnosisScope scope,
                         List<DiagnosisBlocker> blockers) {
        this(problemStatement, hypotheses, steps, missingInputs, scope, blockers, 0, 0);
    }

    public DiagnosisPlan(String problemStatement, List<Hypothesis> hypotheses, List<DiagnosisStep> steps,
                         List<String> missingInputs, DiagnosisScope scope,
                         List<DiagnosisBlocker> blockers, long capabilityGeneration) {
        this(problemStatement, hypotheses, steps, missingInputs, scope, blockers,
                capabilityGeneration, 0);
    }

    public DiagnosisPlan(String problemStatement, List<Hypothesis> hypotheses, List<DiagnosisStep> steps,
                         List<String> missingInputs, DiagnosisScope scope) {
        this(problemStatement, hypotheses, steps, missingInputs, scope, List.of(), 0);
    }

    public DiagnosisPlan(String problemStatement, List<Hypothesis> hypotheses, List<DiagnosisStep> steps,
                         List<String> missingInputs) {
        this(problemStatement, hypotheses, steps, missingInputs,
                DiagnosisScope.unknown(), List.of(), 0);
    }

    public DiagnosisPlan(String problemStatement, List<Hypothesis> hypotheses, List<DiagnosisStep> steps) {
        this(problemStatement, hypotheses, steps, List.of(),
                DiagnosisScope.unknown(), List.of(), 0);
    }

    public boolean isToolAllowed(String toolName) {
        return steps.stream().anyMatch(step -> step.canUseTool(toolName));
    }

    public boolean needsMoreInformation() {
        return !missingInputs.isEmpty();
    }

    public boolean isBlocked() {
        return blockers.stream().anyMatch(
                blocker -> blocker.type() != DiagnosisBlockerType.USER_INPUT_REQUIRED);
    }

    public DiagnosisPlan withBlockers(List<DiagnosisBlocker> nextBlockers) {
        return new DiagnosisPlan(
                problemStatement, hypotheses, steps, missingInputs, scope,
                nextBlockers, capabilityGeneration, resourceGeneration);
    }

    public DiagnosisPlan withCapabilityGeneration(long generation) {
        return new DiagnosisPlan(
                problemStatement, hypotheses, steps, missingInputs, scope, blockers,
                generation, resourceGeneration);
    }

    public DiagnosisPlan withGenerations(long toolGeneration, long resourceGeneration) {
        return new DiagnosisPlan(
                problemStatement, hypotheses, steps, missingInputs, scope, blockers,
                toolGeneration, resourceGeneration);
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
