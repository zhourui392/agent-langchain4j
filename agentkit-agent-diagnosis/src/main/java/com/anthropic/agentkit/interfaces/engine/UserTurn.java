package com.anthropic.agentkit.interfaces.engine;

import java.util.Objects;

/**
 * A user message from conversation history.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public record UserTurn(String text) implements TurnMessage {
    public UserTurn {
        Objects.requireNonNull(text, "text");
    }
}
