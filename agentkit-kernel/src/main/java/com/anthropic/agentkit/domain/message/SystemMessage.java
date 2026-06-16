package com.anthropic.agentkit.domain.message;

import java.util.Objects;

public record SystemMessage(String text) implements ChatMessage {
    public SystemMessage {
        Objects.requireNonNull(text, "text");
    }

    public static SystemMessage of(String text) {
        return new SystemMessage(text);
    }

    @Override
    public Role role() {
        return Role.SYSTEM;
    }
}
