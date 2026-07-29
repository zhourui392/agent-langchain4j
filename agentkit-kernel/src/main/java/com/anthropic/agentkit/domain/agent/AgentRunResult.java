package com.anthropic.agentkit.domain.agent;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.suspension.ResumeToken;
import com.anthropic.agentkit.domain.suspension.RunSuspension;

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
        Optional<String> errorDetail,
        Optional<RunSuspension> suspension,
        Optional<ResumeToken> resumeToken) {

    public AgentRunResult(
            RunId runId, StopReason stopReason, AiMessage finalMessage,
            Optional<Map<String, Object>> structuredOutput, AgentUsage usage,
            BudgetConsumption consumption, Optional<String> errorDetail) {
        this(runId, stopReason, finalMessage, structuredOutput, usage, consumption,
                errorDetail, Optional.empty(), Optional.empty());
    }

    public AgentRunResult(RunId runId, StopReason stopReason, AiMessage finalMessage,
                          Optional<Map<String, Object>> structuredOutput,
                          AgentUsage usage, BudgetConsumption consumption) {
        this(runId, stopReason, finalMessage, structuredOutput, usage, consumption,
                Optional.empty(), Optional.empty(), Optional.empty());
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
        Objects.requireNonNull(suspension, "suspension");
        Objects.requireNonNull(resumeToken, "resumeToken");
        if (suspension.isPresent() && suspension.orElseThrow().stopReason() != stopReason) {
            throw new IllegalArgumentException("suspension does not match stop reason");
        }
        if (resumeToken.isPresent() && suspension.isEmpty()) {
            throw new IllegalArgumentException("resume token requires a suspension");
        }
    }

    public static AgentRunResult suspended(
            RunId runId, RunSuspension suspension, ResumeToken token,
            AgentUsage usage, BudgetConsumption consumption) {
        Objects.requireNonNull(suspension, "suspension");
        return new AgentRunResult(
                runId, suspension.stopReason(), suspension.finalMessage(), Optional.empty(),
                usage, consumption, Optional.empty(), Optional.of(suspension),
                Optional.of(Objects.requireNonNull(token, "token")));
    }
}
