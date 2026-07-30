package com.anthropic.agentkit.domain.diagnosis;

/**
 * Deterministic result of resolving a service inside one environment.
 *
 * @author alex
 */
public enum ServiceResolutionStatus {
    NOT_CONFIGURED,
    RESOLVED,
    AMBIGUOUS,
    UNKNOWN
}
