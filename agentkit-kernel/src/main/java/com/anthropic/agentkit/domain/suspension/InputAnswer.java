package com.anthropic.agentkit.domain.suspension;

import java.util.Objects;

/** User-provided answer appended by a resumed run segment. */
public record InputAnswer(String value) {

    public InputAnswer {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("input answer must not be blank");
        }
    }

    public static InputAnswer of(String value) {
        return new InputAnswer(value);
    }
}
