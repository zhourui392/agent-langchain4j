package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.domain.port.ContextWindowExceededException;
import com.anthropic.agentkit.domain.port.ProviderFailureException;
import com.anthropic.agentkit.domain.port.ProviderFailureKind;

import java.util.List;
import java.util.Locale;

/** Translates provider-specific context overflow diagnostics into the kernel port protocol. */
final class ProviderFailureMapper {

    private static final List<String> CONTEXT_OVERFLOW_MARKERS = List.of(
            "context_length_exceeded",
            "maximum context length",
            "context window exceeded",
            "prompt is too long",
            "input is too long",
            "too many tokens",
            "exceed context limit");
    private static final List<String> AUTHENTICATION_MARKERS = List.of(
            "unauthorized", "invalid api key", "authentication failed",
            "status 401", "status code 401", "http 401",
            "status 403", "status code 403", "http 403");
    private static final List<String> RATE_LIMIT_MARKERS = List.of(
            "rate limit", "rate_limit", "too many requests",
            "status 429", "status code 429", "http 429");
    private static final List<String> TRANSIENT_MARKERS = List.of(
            "request timeout", "connection reset", "connection refused",
            "service unavailable", "temporarily unavailable", "overloaded",
            "bad gateway", "gateway timeout", "status 408", "status code 408",
            "status 500", "status code 500", "status 502", "status code 502",
            "status 503", "status code 503", "status 504", "status code 504");

    private ProviderFailureMapper() {
    }

    static Throwable toDomain(Throwable failure) {
        if (failure instanceof ContextWindowExceededException
                || failure instanceof ProviderFailureException) {
            return failure;
        }
        if (isContextOverflow(failure)) {
            return new ContextWindowExceededException(messageOf(failure), failure);
        }
        ProviderFailureKind kind = classify(failure);
        return kind == ProviderFailureKind.UNKNOWN ? failure
                : new ProviderFailureException(kind, messageOf(failure), failure);
    }

    static ProviderFailureException schemaIncompatible(Throwable failure) {
        return new ProviderFailureException(
                ProviderFailureKind.SCHEMA_INCOMPATIBLE,
                messageOf(failure), failure);
    }

    private static ProviderFailureKind classify(Throwable failure) {
        String diagnostics = diagnostics(failure);
        if (containsMarker(diagnostics, AUTHENTICATION_MARKERS)) {
            return ProviderFailureKind.AUTHENTICATION;
        }
        if (containsMarker(diagnostics, RATE_LIMIT_MARKERS)) {
            return ProviderFailureKind.RATE_LIMITED;
        }
        if (containsMarker(diagnostics, TRANSIENT_MARKERS)) {
            return ProviderFailureKind.TRANSIENT;
        }
        return ProviderFailureKind.UNKNOWN;
    }

    private static boolean isContextOverflow(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && containsMarker(message.toLowerCase(Locale.ROOT))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsMarker(String message) {
        return CONTEXT_OVERFLOW_MARKERS.stream().anyMatch(message::contains);
    }

    private static boolean containsMarker(String message, List<String> markers) {
        return markers.stream().anyMatch(message::contains);
    }

    private static String diagnostics(Throwable failure) {
        StringBuilder diagnostics = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                diagnostics.append(' ').append(current.getMessage().toLowerCase(Locale.ROOT));
            }
            current = current.getCause();
        }
        return diagnostics.toString();
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? "provider context window exceeded" : message;
    }
}
