package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.domain.port.ContextWindowExceededException;

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

    private ProviderFailureMapper() {
    }

    static Throwable toDomain(Throwable failure) {
        if (failure instanceof ContextWindowExceededException || !isContextOverflow(failure)) {
            return failure;
        }
        return new ContextWindowExceededException(messageOf(failure), failure);
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

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? "provider context window exceeded" : message;
    }
}
