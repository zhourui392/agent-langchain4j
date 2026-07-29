package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.domain.port.ContextWindowExceededException;
import com.anthropic.agentkit.domain.port.ProviderFailureException;
import com.anthropic.agentkit.domain.port.ProviderFailureKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderFailureMapperTest {

    @Test
    void classifiesKnownProviderDiagnosticsWithoutSdkTypes() {
        assertKind("HTTP 401 unauthorized", ProviderFailureKind.AUTHENTICATION);
        assertKind("HTTP 429 too many requests", ProviderFailureKind.RATE_LIMITED);
        assertKind("status code 503 service unavailable", ProviderFailureKind.TRANSIENT);
        assertKind("connection reset by peer", ProviderFailureKind.TRANSIENT);
    }

    @Test
    void contextOverflowKeepsItsDedicatedSignal() {
        Throwable mapped = ProviderFailureMapper.toDomain(
                new RuntimeException("context_length_exceeded: prompt too long"));

        assertThat(mapped).isInstanceOf(ContextWindowExceededException.class)
                .isNotInstanceOf(ProviderFailureException.class);
    }

    @Test
    void unknownFailureRemainsNonRetryableAndUnwrapped() {
        RuntimeException original = new RuntimeException("unexpected response");

        assertThat(ProviderFailureMapper.toDomain(original)).isSameAs(original);
    }

    private static void assertKind(String message, ProviderFailureKind kind) {
        assertThat(ProviderFailureMapper.toDomain(new RuntimeException(message)))
                .isInstanceOfSatisfying(ProviderFailureException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(kind));
    }
}
