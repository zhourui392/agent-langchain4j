package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Applies a finite retry policy and fixed host health probe to a read-only log client.
 *
 * @author alex
 * @since 2026-07-30
 */
public final class ResilientLogQueryClient
        implements LogQueryClient, BackendHealthIndicator {

    private final LogQueryClient delegate;
    private final BackendRetryPolicy retryPolicy;
    private final BackendHealthProbe healthProbe;
    private final LongSupplier nanoTime;

    public ResilientLogQueryClient(LogQueryClient delegate,
                                   BackendRetryPolicy retryPolicy) {
        this(delegate, retryPolicy, BackendHealth::ready);
    }

    public ResilientLogQueryClient(LogQueryClient delegate,
                                   BackendRetryPolicy retryPolicy,
                                   BackendHealthProbe healthProbe) {
        this(delegate, retryPolicy, healthProbe, System::nanoTime);
    }

    ResilientLogQueryClient(LogQueryClient delegate, BackendRetryPolicy retryPolicy,
                            BackendHealthProbe healthProbe, LongSupplier nanoTime) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.healthProbe = Objects.requireNonNull(healthProbe, "healthProbe");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public String query(LogQueryRequest request) throws IOException {
        return queryResult(request).content();
    }

    @Override
    public LogQueryResult queryResult(LogQueryRequest request) throws IOException {
        long deadline = deadline();
        int retries = 0;
        while (true) {
            try {
                return delegate.queryResult(request).withRetryCount(retries);
            } catch (BackendQueryException failure) {
                if (!canRetry(failure, retries, deadline)) {
                    throw failure.withRetryCount(retries);
                }
                retries++;
            }
        }
    }

    @Override
    public String dataSourceId() {
        return delegate.dataSourceId();
    }

    @Override
    public String environment() {
        return delegate.environment();
    }

    @Override
    public String service() {
        return delegate.service();
    }

    @Override
    public BackendHealth health() {
        return healthProbe.probe();
    }

    private long deadline() {
        long start = nanoTime.getAsLong();
        long elapsed = retryPolicy.maxElapsed().toNanos();
        long deadline = start + elapsed;
        return deadline < start ? Long.MAX_VALUE : deadline;
    }

    private boolean canRetry(BackendQueryException failure, int retries, long deadline) {
        return failure.failure().retryable()
                && retries < retryPolicy.maxRetries()
                && nanoTime.getAsLong() - deadline < 0L;
    }
}
