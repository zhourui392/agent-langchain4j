package com.anthropic.cclc.application;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.domain.tool.ToolUseRequest;

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

    default void onError(Throwable error) {
    }
}
