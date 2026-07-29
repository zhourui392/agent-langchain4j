package com.anthropic.agentkit.application.interception;

import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentRunState;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.SessionId;

import java.util.Objects;
import java.util.Optional;

/** Parent/child correlation emitted for each child run segment. */
public record SubAgentLifecycleEvent(
        AgentId agentId,
        RunId parentRunId,
        RunId childRunId,
        SessionId childSessionId,
        AgentRunState state,
        Optional<StopReason> stopReason) {

    public SubAgentLifecycleEvent {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(parentRunId, "parentRunId");
        Objects.requireNonNull(childRunId, "childRunId");
        Objects.requireNonNull(childSessionId, "childSessionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(stopReason, "stopReason");
    }
}
