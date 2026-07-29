package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.domain.agent.AgentRunResult;

import java.util.Objects;
import java.util.Optional;

public record CliAgentResult(Optional<AgentRunResult> result) {

    public CliAgentResult {
        Objects.requireNonNull(result, "result");
    }

    public static CliAgentResult completed(AgentRunResult result) {
        return new CliAgentResult(Optional.of(Objects.requireNonNull(result, "result")));
    }

    public static CliAgentResult empty() {
        return new CliAgentResult(Optional.empty());
    }
}
