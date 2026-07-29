package com.anthropic.agentkit.domain.agent;

import com.anthropic.agentkit.domain.conversation.SessionId;

import java.util.concurrent.CompletionStage;

/** Lifecycle handle for one child session and its serial run segments. */
public interface SubAgentHandle {

    AgentId id();

    RunId parentRunId();

    RunId childRunId();

    SessionId sessionId();

    AgentRunState state();

    CompletionStage<AgentRunResult> result();

    CompletionStage<AgentRunResult> followUp(String message);

    boolean cancel();
}
