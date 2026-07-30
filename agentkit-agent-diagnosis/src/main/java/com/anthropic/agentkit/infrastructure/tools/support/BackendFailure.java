package com.anthropic.agentkit.infrastructure.tools.support;

import java.util.Objects;

/**
 * Sanitized diagnosis backend failure exposed to orchestration and audit code.
 *
 * @param code stable failure category
 * @param retryable whether a read-only request may be retried within its budget
 * @param safeMessage message that contains no endpoint, credential, query, or response body
 * @author alex
 * @since 2026-07-30
 */
public record BackendFailure(
        BackendErrorCode code,
        boolean retryable,
        String safeMessage) {

    public BackendFailure {
        Objects.requireNonNull(code, "code");
        safeMessage = Objects.requireNonNull(safeMessage, "safeMessage").trim();
        if (safeMessage.isEmpty()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
    }
}
