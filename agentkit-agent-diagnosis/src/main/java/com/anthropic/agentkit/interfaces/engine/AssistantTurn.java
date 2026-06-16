package com.anthropic.agentkit.interfaces.engine;

import java.util.List;
import java.util.Objects;

/**
 * An assistant message from history, optionally with the tool calls it issued.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public record AssistantTurn(String text, List<ToolCall> toolCalls) implements TurnMessage {
    public AssistantTurn {
        Objects.requireNonNull(text, "text");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AssistantTurn text(String text) {
        return new AssistantTurn(text, List.of());
    }
}
