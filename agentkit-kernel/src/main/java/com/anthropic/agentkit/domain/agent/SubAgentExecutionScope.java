package com.anthropic.agentkit.domain.agent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit child depth and shared active-run quota propagated through tool execution. */
public final class SubAgentExecutionScope {

    private final int depth;
    private final SubAgentLimits limits;
    private final Quota quota;

    private SubAgentExecutionScope(int depth, SubAgentLimits limits, Quota quota) {
        this.depth = depth;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.quota = Objects.requireNonNull(quota, "quota");
    }

    public static SubAgentExecutionScope root(SubAgentLimits limits) {
        return new SubAgentExecutionScope(0, limits, new Quota());
    }

    public int depth() {
        return depth;
    }

    public SubAgentLimits limits() {
        return limits;
    }

    public SubAgentExecutionScope child(SubAgentLimits requested) {
        SubAgentLimits effective = limits.narrowedBy(requested);
        int childDepth = depth + 1;
        if (childDepth > effective.maxDepth()) {
            throw new SubAgentLimitExceededException(
                    "sub-agent depth limit exceeded: " + effective.maxDepth());
        }
        return new SubAgentExecutionScope(childDepth, effective, quota);
    }

    public Lease acquire() {
        quota.acquire(limits.maxConcurrency());
        return new Lease(quota);
    }

    /** Idempotent ownership of one active child-run quota slot. */
    public static final class Lease implements AutoCloseable {
        private final Quota quota;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Lease(Quota quota) {
            this.quota = quota;
        }

        @Override
        public void close() {
            if (open.compareAndSet(true, false)) {
                quota.release();
            }
        }
    }

    private static final class Quota {
        private int active;

        private synchronized void acquire(int maximum) {
            if (active >= maximum) {
                throw new SubAgentLimitExceededException(
                        "sub-agent concurrency limit exceeded: " + maximum);
            }
            active++;
        }

        private synchronized void release() {
            active--;
        }
    }
}
