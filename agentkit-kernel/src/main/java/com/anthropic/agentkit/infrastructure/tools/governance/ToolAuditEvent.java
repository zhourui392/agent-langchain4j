package com.anthropic.agentkit.infrastructure.tools.governance;

import java.util.Objects;

/**
 * Sanitized audit metadata for one tool execution.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record ToolAuditEvent(String toolName, boolean success, long durationMs, String error,
                             String runId, String sessionId, long resultBytes) {

    public ToolAuditEvent {
        Objects.requireNonNull(toolName, "toolName");
        error = error == null ? "" : error;
        runId = runId == null ? "" : runId;
        sessionId = sessionId == null ? "" : sessionId;
        if (durationMs < 0 || resultBytes < 0) {
            throw new IllegalArgumentException("audit durations and byte counts must be non-negative");
        }
    }

    public ToolAuditEvent(String toolName, boolean success, long durationMs, String error) {
        this(toolName, success, durationMs, error, "", "", 0);
    }
}
