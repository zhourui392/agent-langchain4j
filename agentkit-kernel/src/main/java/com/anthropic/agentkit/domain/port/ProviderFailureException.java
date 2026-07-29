package com.anthropic.agentkit.domain.port;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Typed provider failure without leaking a provider SDK exception type into kernel policy. */
public final class ProviderFailureException extends RuntimeException {

    private final ProviderFailureKind kind;
    private final Optional<Duration> retryAfter;

    public ProviderFailureException(ProviderFailureKind kind, String message) {
        this(kind, message, Optional.empty(), null);
    }

    public ProviderFailureException(
            ProviderFailureKind kind, String message, Throwable cause) {
        this(kind, message, Optional.empty(), cause);
    }

    public ProviderFailureException(
            ProviderFailureKind kind, String message,
            Optional<Duration> retryAfter, Throwable cause) {
        super(requireMessage(message), cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        retryAfter.ifPresent(delay -> {
            if (delay.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative");
            }
        });
    }

    public ProviderFailureKind kind() {
        return kind;
    }

    public Optional<Duration> retryAfter() {
        return retryAfter;
    }

    private static String requireMessage(String message) {
        Objects.requireNonNull(message, "message");
        return message.isBlank() ? "provider failed" : message;
    }
}
