package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.util.Objects;

/**
 * Checked backend failure whose public message is already sanitized.
 *
 * @author alex
 * @since 2026-07-30
 */
public final class BackendQueryException extends IOException {

    private final BackendFailure failure;
    private final Integer statusCode;
    private final int retryCount;

    public BackendQueryException(BackendFailure failure) {
        this(failure, null, 0, null);
    }

    public BackendQueryException(BackendFailure failure, Integer statusCode) {
        this(failure, statusCode, 0, null);
    }

    public BackendQueryException(BackendFailure failure, Throwable cause) {
        this(failure, null, 0, cause);
    }

    private BackendQueryException(
            BackendFailure failure,
            Integer statusCode,
            int retryCount,
            Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").safeMessage(), cause);
        this.failure = failure;
        this.statusCode = statusCode;
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must be non-negative");
        }
        this.retryCount = retryCount;
    }

    public BackendFailure failure() {
        return failure;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public int retryCount() {
        return retryCount;
    }

    public BackendQueryException withRetryCount(int retries) {
        return new BackendQueryException(failure, statusCode, retries, getCause());
    }
}
