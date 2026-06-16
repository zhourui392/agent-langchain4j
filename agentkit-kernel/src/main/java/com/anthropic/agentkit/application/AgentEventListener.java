package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

public interface AgentEventListener {

    AgentEventListener NO_OP = new AgentEventListener() {
    };

    default void onLlmRequestStart() {
    }

    default void onAssistantTextDelta(String delta) {
    }

    default void onToolUseStart(ToolUseRequest request) {
    }

    default void onToolUseEnd(ToolUseRequest request, ToolResult result, long durationMs) {
    }

    default void onTurnComplete(AiMessage finalMessage) {
    }

    default void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
    }

    default void onError(Throwable error) {
    }
}
