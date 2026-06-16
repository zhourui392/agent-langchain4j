package com.anthropic.agentkit.domain.agent;

/**
 * Raised when an agent run tries to exceed its configured budget.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class AgentBudgetExceededException extends RuntimeException {

    public AgentBudgetExceededException(String message) {
        super(message);
    }
}
