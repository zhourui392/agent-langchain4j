package com.anthropic.agentkit.interfaces.engine;

/**
 * Structured terminal reason for a diagnosis run.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public enum ExitReason {
    SUCCESS,
    STOPPED,
    TIMEOUT,
    ERROR,
    REJECTED
}
