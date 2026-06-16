package com.anthropic.agentkit.interfaces.engine;

import java.util.Objects;

/**
 * A tool result from history, paired to the {@link ToolCall} with the same id.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public record ToolResultTurn(String toolUseId, String content) implements TurnMessage {
    public ToolResultTurn {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(content, "content");
    }
}
