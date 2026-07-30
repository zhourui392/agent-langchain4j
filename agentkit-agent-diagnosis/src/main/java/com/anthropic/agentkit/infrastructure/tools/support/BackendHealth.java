package com.anthropic.agentkit.infrastructure.tools.support;

import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Secret-free result of one host-defined, read-only backend health probe.
 *
 * @author alex
 * @since 2026-07-30
 */
public record BackendHealth(ReadinessStatus status, String reasonCode, Instant observedAt) {

    public BackendHealth {
        status = Objects.requireNonNull(status, "status");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode").trim();
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if (!reasonCode.matches("[A-Z0-9_]{1,128}")) {
            throw new IllegalArgumentException("reasonCode must be a stable safe code");
        }
    }

    public static BackendHealth ready() {
        return new BackendHealth(ReadinessStatus.READY, "BACKEND_READY", Instant.now());
    }
}
