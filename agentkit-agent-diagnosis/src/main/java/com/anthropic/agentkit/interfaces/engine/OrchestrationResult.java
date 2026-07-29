package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.agent.AgentRunResult;

import java.util.Objects;

/**
 * Internal return value from the diagnosis orchestrator.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
record OrchestrationResult(String stateSnapshot, AgentRunResult agentRunResult) {

    OrchestrationResult {
        stateSnapshot = stateSnapshot == null ? "" : stateSnapshot;
        Objects.requireNonNull(agentRunResult, "agentRunResult");
    }
}
