package com.anthropic.agentkit.domain.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPolicyTest {

    @Test
    void defaultPolicyHasFiniteRetryAndNoFallback() {
        ModelPolicy policy = ModelPolicy.defaults(ModelTier.BALANCED);

        assertThat(policy.retryPolicy().maxAttempts()).isBetween(1, 10);
        assertThat(policy.fallbackTiers()).isEmpty();
        assertThat(policy.tierForAttempt(1)).isEqualTo(ModelTier.BALANCED);
        assertThat(policy.tierForAttempt(3)).isEqualTo(ModelTier.BALANCED);
    }

    @Test
    void configuredFallbacksAdvanceThenStayOnLastTier() {
        ModelPolicy policy = new ModelPolicy(
                ModelTier.FAST, List.of(ModelTier.BALANCED, ModelTier.CAPABLE),
                RetryPolicy.fixed(4, Duration.ZERO));

        assertThat(List.of(
                policy.tierForAttempt(1), policy.tierForAttempt(2),
                policy.tierForAttempt(3), policy.tierForAttempt(4)))
                .containsExactly(ModelTier.FAST, ModelTier.BALANCED,
                        ModelTier.CAPABLE, ModelTier.CAPABLE);
    }
}
