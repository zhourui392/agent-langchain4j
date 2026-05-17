package com.anthropic.cclc.application;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.domain.tool.ToolUseRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RecordingAgentEventListener implements AgentEventListener {

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    List<String> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    @Override
    public void onLlmRequestStart() {
        events.add("llmRequestStart");
    }

    @Override
    public void onAssistantTextDelta(String delta) {
        events.add("assistantTextDelta:" + delta);
    }

    @Override
    public void onToolUseStart(ToolUseRequest request) {
        events.add("toolUseStart:" + request.toolName());
    }

    @Override
    public void onToolUseEnd(ToolUseRequest request, ToolResult result, long durationMs) {
        events.add("toolUseEnd:" + request.toolName() + ":" + (result.success() ? "ok" : "error"));
    }

    @Override
    public void onTurnComplete(AiMessage finalMessage) {
        events.add("turnComplete:" + finalMessage.text());
    }

    @Override
    public void onError(Throwable error) {
        events.add("error:" + error.getClass().getSimpleName());
    }
}
