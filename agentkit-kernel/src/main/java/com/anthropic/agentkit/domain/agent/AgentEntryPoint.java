package com.anthropic.agentkit.domain.agent;

/**
 * Typed host-facing boundary exposed by an agent package.
 *
 * @param <I> request type owned by the agent package
 * @param <O> result type owned by the agent package
 */
public interface AgentEntryPoint<I, O> extends AutoCloseable {

    Class<I> requestType();

    Class<O> resultType();

    O invoke(I request);

    @Override
    default void close() {
    }
}
