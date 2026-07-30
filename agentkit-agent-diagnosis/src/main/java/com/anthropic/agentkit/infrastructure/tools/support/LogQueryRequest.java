package com.anthropic.agentkit.infrastructure.tools.support;

import java.util.Objects;

/**
 * Typed request for a bounded log search.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record LogQueryRequest(String traceId,
                              String keyword,
                              String service,
                              String startTime,
                              String endTime,
                              String level,
                              int limit) {

    public LogQueryRequest {
        traceId = clean(traceId);
        keyword = clean(keyword);
        service = clean(service);
        startTime = clean(startTime);
        endTime = clean(endTime);
        level = clean(level);
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    public boolean hasQueryAnchor() {
        return !traceId.isBlank() || !keyword.isBlank() || !level.isBlank();
    }

    private static String clean(String value) {
        return Objects.toString(value, "").trim();
    }
}
