package com.anthropic.agentkit.domain.agent;

import java.util.List;
import java.util.Objects;

/** Provider-neutral primary and optional fallback route for one agent role. */
public record ModelPolicy(
        ModelTier primaryTier,
        List<ModelTier> fallbackTiers,
        RetryPolicy retryPolicy) {

    public ModelPolicy {
        Objects.requireNonNull(primaryTier, "primaryTier");
        fallbackTiers = List.copyOf(
                Objects.requireNonNull(fallbackTiers, "fallbackTiers"));
        if (fallbackTiers.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("fallback tiers must not contain null");
        }
        Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    public static ModelPolicy defaults(ModelTier primaryTier) {
        return new ModelPolicy(primaryTier, List.of(), RetryPolicy.standard());
    }

    public static ModelPolicy noRetry(ModelTier primaryTier) {
        return new ModelPolicy(primaryTier, List.of(), RetryPolicy.none());
    }

    public ModelTier tierForAttempt(int attempt) {
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        if (attempt == 1 || fallbackTiers.isEmpty()) {
            return primaryTier;
        }
        int fallbackIndex = Math.min(attempt - 2, fallbackTiers.size() - 1);
        return fallbackTiers.get(fallbackIndex);
    }
}
