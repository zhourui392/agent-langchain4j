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
        if ((state == AgentRunState.STARTING || state == AgentRunState.RUNNING)
                && stopReason.isPresent()) {
            throw new IllegalArgumentException(
                    "an active sub-agent lifecycle event cannot have a stop reason");
        }
        if (state == AgentRunState.COMPLETED && stopReason.isEmpty()) {
            throw new IllegalArgumentException(
                    "a completed sub-agent lifecycle event requires a stop reason");
        }
    }

    public static SubAgentLifecycleEvent spawned(
            AgentId agentId, RunId parentRunId, RunId childRunId,
            SessionId childSessionId) {
        return new SubAgentLifecycleEvent(
                agentId, parentRunId, childRunId, childSessionId,
                AgentRunState.RUNNING, Optional.empty());
    }

    public static SubAgentLifecycleEvent stopped(
            AgentId agentId, RunId parentRunId, RunId childRunId,
            SessionId childSessionId, AgentRunState state,
            Optional<StopReason> stopReason) {
        if (state == AgentRunState.STARTING || state == AgentRunState.RUNNING) {
            throw new IllegalArgumentException(
                    "a stopped sub-agent event requires a terminal lifecycle state");
        }
        return new SubAgentLifecycleEvent(
                agentId, parentRunId, childRunId, childSessionId, state, stopReason);
    }
}
