package com.anthropic.agentkit.domain.diagnosis;

/**
 * Machine-readable ownership and recovery class for a blocked diagnosis.
 *
 * @author alex
 */
public enum DiagnosisBlockerType {
    USER_INPUT_REQUIRED,
    CAPABILITY_UNAVAILABLE,
    BACKEND_UNHEALTHY,
    ENVIRONMENT_MISMATCH,
    POLICY_DENIED
}
