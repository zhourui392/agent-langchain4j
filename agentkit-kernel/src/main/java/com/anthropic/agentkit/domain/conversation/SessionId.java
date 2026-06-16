package com.anthropic.agentkit.domain.conversation;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;

public record SessionId(String value) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public SessionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
    }

    public static SessionId of(String value) {
        return new SessionId(value);
    }

    public static SessionId fresh() {
        return new SessionId(generateTimeOrderedUuid().toString());
    }

    @Override
    public String toString() {
        return value;
    }

    private static UUID generateTimeOrderedUuid() {
        long unixMillis = System.currentTimeMillis();
        long randHigh = RANDOM.nextLong() & 0x0000_0000_0000_0FFFL;
        long mostSig = (unixMillis << 16) | 0x7000L | randHigh;
        long randLow = RANDOM.nextLong();
        long leastSig = (randLow & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSig, leastSig);
    }
}
