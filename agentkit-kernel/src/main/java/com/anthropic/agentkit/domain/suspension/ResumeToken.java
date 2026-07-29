package com.anthropic.agentkit.domain.suspension;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/** Single-use bearer credential; never persist or log its raw value. */
public record ResumeToken(String value) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public ResumeToken {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("resume token must not be blank");
        }
    }

    public static ResumeToken fresh() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new ResumeToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    public static ResumeToken of(String value) {
        return new ResumeToken(value);
    }

    @Override public String toString() { return "ResumeToken[REDACTED]"; }
}
