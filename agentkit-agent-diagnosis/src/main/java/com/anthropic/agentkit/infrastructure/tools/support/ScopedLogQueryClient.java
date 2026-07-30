package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.util.Objects;

/**
 * Adds host-owned logical scope to a log client without exposing its connection.
 *
 * @author alex
 * @since 2026-07-30
 */
public final class ScopedLogQueryClient implements LogQueryClient, BackendHealthIndicator {

    private final LogQueryClient delegate;
    private final String dataSourceId;
    private final String environment;
    private final String service;

    public ScopedLogQueryClient(LogQueryClient delegate, String dataSourceId,
                                String environment) {
        this(delegate, dataSourceId, environment, "unknown");
    }

    public ScopedLogQueryClient(LogQueryClient delegate, String dataSourceId,
                                String environment, String service) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dataSourceId = required(dataSourceId, "dataSourceId");
        this.environment = required(environment, "environment");
        this.service = required(service, "service");
    }

    @Override
    public String query(LogQueryRequest request) throws IOException {
        return queryResult(request).content();
    }

    @Override
    public LogQueryResult queryResult(LogQueryRequest request) throws IOException {
        return delegate.queryResult(request).withScope(dataSourceId, environment);
    }

    @Override
    public String dataSourceId() {
        return dataSourceId;
    }

    @Override
    public String environment() {
        return environment;
    }

    @Override
    public String service() {
        return service;
    }

    @Override
    public BackendHealth health() {
        return delegate instanceof BackendHealthIndicator indicator
                ? indicator.health() : BackendHealth.ready();
    }

    private static String required(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
