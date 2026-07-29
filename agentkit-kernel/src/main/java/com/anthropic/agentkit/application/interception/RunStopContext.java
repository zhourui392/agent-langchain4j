package com.anthropic.agentkit.application.interception;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;

import java.util.Objects;

/** Proposed terminal projection before its required run-stopped event is written. */
public record RunStopContext(
        AgentRunContext runContext, AgentRunResult proposedResult) {

    public RunStopContext {
        Objects.requireNonNull(runContext, "runContext");
        Objects.requireNonNull(proposedResult, "proposedResult");
    }
}
