package com.anthropic.cclc.infrastructure.tools.governance;

/**
 * Receives non-sensitive tool execution audit events.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@FunctionalInterface
public interface ToolAuditSink {

    ToolAuditSink NO_OP = event -> {
    };

    void record(ToolAuditEvent event);
}
