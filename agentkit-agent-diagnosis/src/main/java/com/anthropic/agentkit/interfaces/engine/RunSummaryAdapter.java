package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentUsage;
import com.anthropic.agentkit.domain.agent.StopReason;

import java.util.Objects;

/** Explicit projection from the kernel run terminal model to the diagnosis API. */
final class RunSummaryAdapter {

    private RunSummaryAdapter() {
    }

    static RunSummary from(OrchestrationResult result) {
        Objects.requireNonNull(result, "result");
        AgentRunResult run = result.agentRunResult();
        return new RunSummary(
                exitReason(run.stopReason()), result.stateSnapshot(), usage(run.usage()),
                run.errorDetail().orElse(""));
    }

    private static ExitReason exitReason(StopReason reason) {
        return switch (reason) {
            case MODEL_COMPLETED, TERMINAL_TOOL, WAITING_FOR_INPUT,
                    WAITING_FOR_APPROVAL, BUDGET_EXHAUSTED -> ExitReason.SUCCESS;
            case CANCELLED, CONTEXT_EXHAUSTED -> ExitReason.STOPPED;
            case TIMED_OUT -> ExitReason.TIMEOUT;
            case PROVIDER_ERROR, TOOL_PROTOCOL_ERROR -> ExitReason.ERROR;
        };
    }

    private static RunSummary.Usage usage(AgentUsage usage) {
        return new RunSummary.Usage(
                usage.inputTokens(), usage.outputTokens(), usage.cacheReadInputTokens());
    }
}
