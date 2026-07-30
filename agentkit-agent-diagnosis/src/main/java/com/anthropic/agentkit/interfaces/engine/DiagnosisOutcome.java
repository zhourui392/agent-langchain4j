package com.anthropic.agentkit.interfaces.engine;

/**
 * Diagnosis-domain outcome kept separate from process-level {@link ExitReason}.
 *
 * @author alex
 */
public enum DiagnosisOutcome {
    COMPLETED,
    NON_INCIDENT_RESPONSE,
    WAITING_FOR_USER_INPUT,
    CAPABILITY_UNAVAILABLE,
    BACKEND_UNHEALTHY,
    ENVIRONMENT_MISMATCH,
    POLICY_DENIED,
    BUDGET_LIMITED,
    CANCELLED,
    FAILED
}
