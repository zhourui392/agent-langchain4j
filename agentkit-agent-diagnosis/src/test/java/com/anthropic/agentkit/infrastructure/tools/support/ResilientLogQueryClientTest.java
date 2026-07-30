package com.anthropic.agentkit.infrastructure.tools.support;

import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author alex
 */
class ResilientLogQueryClientTest {

    @Test
    void retriesOneTypedTransientFailureAndPublishesRetryCount() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LogQueryClient delegate = request -> {
            if (calls.getAndIncrement() == 0) {
                throw backend(BackendErrorCode.RATE_LIMITED, true);
            }
            return "recovered";
        };
        ResilientLogQueryClient client = new ResilientLogQueryClient(
                delegate, new BackendRetryPolicy(1, Duration.ofSeconds(1)));

        LogQueryResult result = client.queryResult(request());

        assertThat(calls).hasValue(2);
        assertThat(result.content()).isEqualTo("recovered");
        assertThat(result.retryCount()).isEqualTo(1);
    }

    @Test
    void neverRetriesAuthenticationProtocolOrInvalidQueryFailures() {
        for (BackendErrorCode code : new BackendErrorCode[]{
                BackendErrorCode.AUTHENTICATION_FAILED,
                BackendErrorCode.AUTHORIZATION_DENIED,
                BackendErrorCode.INVALID_QUERY,
                BackendErrorCode.PROTOCOL_ERROR}) {
            AtomicInteger calls = new AtomicInteger();
            LogQueryClient delegate = request -> {
                calls.incrementAndGet();
                throw backend(code, false);
            };
            ResilientLogQueryClient client = new ResilientLogQueryClient(
                    delegate, new BackendRetryPolicy(1, Duration.ofSeconds(1)));

            assertThatThrownBy(() -> client.queryResult(request()))
                    .isInstanceOfSatisfying(BackendQueryException.class,
                            failure -> assertThat(failure.retryCount()).isZero());
            assertThat(calls).as(code.name()).hasValue(1);
        }
    }

    @Test
    void retryExhaustionStopsAtPolicyAndCarriesRetryCount() {
        AtomicInteger calls = new AtomicInteger();
        LogQueryClient delegate = request -> {
            calls.incrementAndGet();
            throw backend(BackendErrorCode.UNAVAILABLE, true);
        };
        ResilientLogQueryClient client = new ResilientLogQueryClient(
                delegate, new BackendRetryPolicy(1, Duration.ofSeconds(1)));

        assertThatThrownBy(() -> client.queryResult(request()))
                .isInstanceOfSatisfying(BackendQueryException.class,
                        failure -> assertThat(failure.retryCount()).isEqualTo(1));
        assertThat(calls).hasValue(2);
    }

    @Test
    void healthProbeIsFixedHostOperationAndFailuresBecomeSecretFreeReadiness() {
        BackendHealthProbe probe = () -> new BackendHealth(
                ReadinessStatus.UNAVAILABLE, "BACKEND_AUTHENTICATION_FAILED",
                Instant.parse("2026-07-30T04:00:00Z"));
        ResilientLogQueryClient client = new ResilientLogQueryClient(
                request -> "unused", BackendRetryPolicy.noRetries(), probe);

        assertThat(client.health().status()).isEqualTo(ReadinessStatus.UNAVAILABLE);
        assertThat(client.health().reasonCode()).isEqualTo("BACKEND_AUTHENTICATION_FAILED");
        assertThat(client.health().toString())
                .doesNotContain("http", "secret", "Authorization");
    }

    private BackendQueryException backend(BackendErrorCode code, boolean retryable) {
        return new BackendQueryException(new BackendFailure(
                code, retryable, "safe backend failure"));
    }

    private LogQueryRequest request() {
        return new LogQueryRequest("trace-1", "", "orders",
                "2026-07-30T00:00:00Z", "2026-07-30T01:00:00Z", "", 20);
    }
}
