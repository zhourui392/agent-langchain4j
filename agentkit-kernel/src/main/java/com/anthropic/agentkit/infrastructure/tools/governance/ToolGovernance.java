package com.anthropic.agentkit.infrastructure.tools.governance;

import java.time.Duration;
import java.util.Objects;

/**
 * Kernel governance policies applied around a tool.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record ToolGovernance(Duration timeout, ToolRedactor redactor, ToolAuditSink auditSink) {

    public ToolGovernance {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        redactor = Objects.requireNonNull(redactor, "redactor");
        auditSink = Objects.requireNonNull(auditSink, "auditSink");
    }

    public static ToolGovernance defaults() {
        return new ToolGovernance(Duration.ofSeconds(30), ToolRedactor.NO_OP, ToolAuditSink.NO_OP);
    }
}
