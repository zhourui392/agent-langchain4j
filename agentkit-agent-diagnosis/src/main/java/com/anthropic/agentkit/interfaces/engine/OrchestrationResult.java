package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisBlocker;

import java.util.List;
import java.util.Objects;

/**
 * Internal return value from the diagnosis orchestrator.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
record OrchestrationResult(String stateSnapshot, AgentRunResult agentRunResult,
                           DiagnosisOutcome outcome, List<DiagnosisBlocker> blockers) {

    OrchestrationResult {
        stateSnapshot = stateSnapshot == null ? "" : stateSnapshot;
        Objects.requireNonNull(agentRunResult, "agentRunResult");
        outcome = Objects.requireNonNull(outcome, "outcome");
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
    }

    OrchestrationResult(String stateSnapshot, AgentRunResult agentRunResult) {
        this(stateSnapshot, agentRunResult, defaultOutcome(agentRunResult.stopReason()), List.of());
    }

    private static DiagnosisOutcome defaultOutcome(StopReason reason) {
        return switch (reason) {
            case WAITING_FOR_INPUT, WAITING_FOR_APPROVAL -> DiagnosisOutcome.WAITING_FOR_USER_INPUT;
            case BUDGET_EXHAUSTED -> DiagnosisOutcome.BUDGET_LIMITED;
            case CANCELLED, TIMED_OUT, CONTEXT_EXHAUSTED -> DiagnosisOutcome.CANCELLED;
            case PROVIDER_ERROR, INTERCEPTOR_ERROR, PERSISTENCE_ERROR,
                    TOOL_PROTOCOL_ERROR, INTERCEPTOR_DENIED -> DiagnosisOutcome.FAILED;
            case MODEL_COMPLETED, TERMINAL_TOOL -> DiagnosisOutcome.COMPLETED;
        };
    }
}
