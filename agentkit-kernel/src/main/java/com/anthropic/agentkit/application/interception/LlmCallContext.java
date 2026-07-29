package com.anthropic.agentkit.application.interception;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;

import java.util.List;
import java.util.Objects;

/** Immutable input presented before one provider call. */
public record LlmCallContext(AgentRunContext runContext, ChatRequest request) {

    public LlmCallContext {
        Objects.requireNonNull(runContext, "runContext");
        Objects.requireNonNull(request, "request");
    }

    LlmCallContext withMessages(List<ChatMessage> messages) {
        return new LlmCallContext(runContext, new ChatRequest(
                request.systemPrompt(), messages, request.tools()));
    }
}
