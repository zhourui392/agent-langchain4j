package com.anthropic.agentkit.infrastructure.agent;

/**
 * Raised when a {@link StructuredAgent} run finishes without the agent ever
 * calling its terminal tool. Centralizes the "agent produced nothing" failure
 * mode that previously lived as scattered null checks in each role's payload-to-VO
 * mapping.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-19
 */
public final class StructuredOutputMissingException extends RuntimeException {

    public StructuredOutputMissingException(String terminalToolName) {
        super("structured agent did not call terminal tool: " + terminalToolName);
    }
}
