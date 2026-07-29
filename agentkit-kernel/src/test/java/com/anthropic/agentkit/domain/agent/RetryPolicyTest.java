package com.anthropic.agentkit.domain.agent;

import com.anthropic.agentkit.domain.port.ProviderFailureException;
import com.anthropic.agentkit.domain.port.ProviderFailureKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    @Test
    void onlyRetriesTypedTransientAndRateLimitedFailuresWithinAttemptLimit() {
        RetryPolicy policy = RetryPolicy.fixed(2, Duration.ZERO);

        assertThat(policy.permitsRetry(1, failure(ProviderFailureKind.TRANSIENT))).isTrue();
        assertThat(policy.permitsRetry(1, failure(ProviderFailureKind.RATE_LIMITED))).isTrue();
        assertThat(policy.permitsRetry(1, failure(ProviderFailureKind.AUTHENTICATION))).isFalse();
        assertThat(policy.permitsRetry(2, failure(ProviderFailureKind.TRANSIENT))).isFalse();
        assertThat(policy.permitsRetry(1, new RuntimeException("unknown"))).isFalse();
    }

    @Test
    void exponentialDelayIsCappedAndHonorsProviderRetryAfter() {
        RetryPolicy policy = new RetryPolicy(
                5, Duration.ofMillis(100), Duration.ofMillis(250), 0);
        ProviderFailureException retryAfter = new ProviderFailureException(
                ProviderFailureKind.RATE_LIMITED, "slow down",
                Optional.of(Duration.ofMillis(400)), null);

        assertThat(policy.delayAfter(1, transientFailure(), () -> 0.5))
                .isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delayAfter(3, transientFailure(), () -> 0.5))
                .isEqualTo(Duration.ofMillis(250));
        assertThat(policy.delayAfter(1, retryAfter, () -> 0.5))
                .isEqualTo(Duration.ofMillis(400));
    }

    @Test
    void jitterSourceIsDeterministicAtBothBounds() {
        RetryPolicy policy = new RetryPolicy(
                2, Duration.ofMillis(100), Duration.ofMillis(100), 0.2);
        ProviderFailureException retryAfter = new ProviderFailureException(
                ProviderFailureKind.RATE_LIMITED, "slow down",
                Optional.of(Duration.ofMillis(100)), null);

        assertThat(policy.delayAfter(1, transientFailure(), () -> 0))
                .isEqualTo(Duration.ofMillis(80));
        assertThat(policy.delayAfter(1, transientFailure(), () -> 1))
                .isEqualTo(Duration.ofMillis(120));
        assertThat(policy.delayAfter(1, retryAfter, () -> 0))
                .isEqualTo(Duration.ofMillis(100));
    }

    private static ProviderFailureException transientFailure() {
        return failure(ProviderFailureKind.TRANSIENT);
    }

    private static ProviderFailureException failure(ProviderFailureKind kind) {
        return new ProviderFailureException(kind, "provider failed");
    }
}
