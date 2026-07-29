package com.anthropic.agentkit.domain.session;

import com.anthropic.agentkit.domain.agent.RunId;

import java.util.Objects;

/** Immutable coordinate of one durable run fact. */
public record RunEventPointer(RunId runId, long sequence) {

    public RunEventPointer {
        Objects.requireNonNull(runId, "runId");
        if (sequence <= 0) {
            throw new IllegalArgumentException("run event sequence must be positive");
        }
    }
}
