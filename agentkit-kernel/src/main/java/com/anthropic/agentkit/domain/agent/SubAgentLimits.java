package com.anthropic.agentkit.domain.agent;

/** Hard process-local nesting and active-run limits for delegated agents. */
public record SubAgentLimits(int maxDepth, int maxConcurrency) {

    public SubAgentLimits {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1");
        }
    }

    public static SubAgentLimits defaults() {
        return new SubAgentLimits(4, 4);
    }

    public SubAgentLimits narrowedBy(SubAgentLimits requested) {
        if (requested == null) {
            throw new NullPointerException("requested");
        }
        return new SubAgentLimits(
                Math.min(maxDepth, requested.maxDepth),
                Math.min(maxConcurrency, requested.maxConcurrency));
    }
}
