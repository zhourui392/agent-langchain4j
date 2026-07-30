package com.anthropic.agentkit.infrastructure.tools.support;

/**
 * Stable, safe-to-publish diagnosis backend failure categories.
 *
 * @author alex
 * @since 2026-07-30
 */
public enum BackendErrorCode {
    AUTHENTICATION_FAILED,
    AUTHORIZATION_DENIED,
    INVALID_QUERY,
    RATE_LIMITED,
    TIMED_OUT,
    CONNECTION_FAILED,
    RESPONSE_TOO_LARGE,
    PROTOCOL_ERROR,
    UNAVAILABLE,
    UNKNOWN
}
