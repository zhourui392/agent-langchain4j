package com.anthropic.agentkit.domain.task;

/** Artifact content exceeded the configured durable size limit. */
public final class ArtifactLimitExceededException extends RuntimeException {

    public ArtifactLimitExceededException(long actual, long maximum) {
        super("artifact content exceeds limit: actual=" + actual + ", maximum=" + maximum);
    }
}
