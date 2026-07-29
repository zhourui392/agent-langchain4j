package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.agent.ModelTier;

import java.util.Objects;

/**
 * Resolves a provider-neutral model tier without exposing provider SDK types.
 * Implementations must return a stable, non-null client whose
 * {@link LlmClient#modelIdentity()} describes the actual provider/model.
 */
@FunctionalInterface
public interface LlmClientSelector {

    LlmClient select(ModelTier tier);

    static LlmClientSelector fixed(LlmClient client) {
        Objects.requireNonNull(client, "client");
        return ignored -> client;
    }
}
