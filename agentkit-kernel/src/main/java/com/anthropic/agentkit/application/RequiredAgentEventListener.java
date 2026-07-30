package com.anthropic.agentkit.application;

/**
 * Marks an event listener whose callbacks are part of the required run result.
 * Callback failures are propagated instead of being treated as optional
 * observer failures.
 *
 * @author alex
 * @since 2026-07-30
 */
public interface RequiredAgentEventListener extends AgentEventListener {
}
