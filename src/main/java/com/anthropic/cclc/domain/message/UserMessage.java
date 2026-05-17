package com.anthropic.cclc.domain.message;

import java.util.Objects;

public record UserMessage(String text) implements ChatMessage {
    public UserMessage {
        Objects.requireNonNull(text, "text");
    }

    public static UserMessage of(String text) {
        return new UserMessage(text);
    }

    @Override
    public Role role() {
        return Role.USER;
    }
}
