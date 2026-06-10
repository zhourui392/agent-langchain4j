package com.anthropic.cclc.infrastructure.tools.governance;

import java.util.Objects;

/**
 * Sanitized audit metadata for one tool execution.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record ToolAuditEvent(String toolName, boolean success, long durationMs, String error) {

    public ToolAuditEvent {
        Objects.requireNonNull(toolName, "toolName");
        error = error == null ? "" : error;
    }
}
