package com.anthropic.agentkit.domain.agent;

/** Structured reason why an agent run reached a terminal state. */
public enum StopReason {
    MODEL_COMPLETED,
    TERMINAL_TOOL,
    WAITING_FOR_INPUT,
    WAITING_FOR_APPROVAL,
    CANCELLED,
    TIMED_OUT,
    BUDGET_EXHAUSTED,
    CONTEXT_EXHAUSTED,
    PROVIDER_ERROR,
    INTERCEPTOR_DENIED,
    INTERCEPTOR_ERROR,
    PERSISTENCE_ERROR,
    TOOL_PROTOCOL_ERROR
}
