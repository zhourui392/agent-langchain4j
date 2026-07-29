package com.anthropic.agentkit.domain.agent;

import com.anthropic.agentkit.domain.message.AiMessage;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable terminal projection of one agent run. */
public record AgentRunResult(
        RunId runId,
        StopReason stopReason,
        AiMessage finalMessage,
        Optional<Map<String, Object>> structuredOutput,
        AgentUsage usage,
        BudgetConsumption consumption,
        Optional<String> errorDetail) {

    public AgentRunResult(RunId runId, StopReason stopReason, AiMessage finalMessage,
                          Optional<Map<String, Object>> structuredOutput,
                          AgentUsage usage, BudgetConsumption consumption) {
        this(runId, stopReason, finalMessage, structuredOutput, usage, consumption,
                Optional.empty());
    }

    public AgentRunResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(finalMessage, "finalMessage");
        Objects.requireNonNull(structuredOutput, "structuredOutput");
        structuredOutput = structuredOutput.map(Map::copyOf);
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(consumption, "consumption");
        Objects.requireNonNull(errorDetail, "errorDetail");
        errorDetail = errorDetail.filter(detail -> !detail.isBlank());
    }
}
