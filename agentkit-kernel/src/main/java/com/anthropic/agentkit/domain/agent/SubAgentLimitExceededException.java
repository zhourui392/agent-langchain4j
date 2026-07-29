package com.anthropic.agentkit.domain.agent;

/** Raised before a child starts when its hard nesting or concurrency bound is exhausted. */
public final class SubAgentLimitExceededException extends RuntimeException {

    public SubAgentLimitExceededException(String message) {
        super(message);
    }
}
