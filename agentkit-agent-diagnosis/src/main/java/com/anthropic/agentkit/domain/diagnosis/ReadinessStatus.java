package com.anthropic.agentkit.domain.diagnosis;

/**
 * Host-visible readiness of one diagnosis data source or capability.
 *
 * @author alex
 */
public enum ReadinessStatus {
    READY,
    DEGRADED,
    UNAVAILABLE
}
