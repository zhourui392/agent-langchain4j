package com.anthropic.agentkit.infrastructure.tools.support;

import java.util.Objects;

/**
 * Bounded log payload plus backend facts used to build ToolResult metadata.
 *
 * @author alex
 * @since 2026-07-30
 */
public record LogQueryResult(String content, String queryId, String dataSourceId, String environment,
                             long matched, int returned, boolean truncated,
                             String backendStatus, String errorCode, int retryCount) {

    public LogQueryResult {
        content = Objects.requireNonNull(content, "content");
        queryId = required(queryId, "queryId");
        dataSourceId = safeScope(dataSourceId);
        environment = safeScope(environment);
        backendStatus = required(backendStatus, "backendStatus");
        errorCode = errorCode == null ? "" : errorCode.trim();
        if (matched < 0L || returned < 0 || returned > matched || retryCount < 0) {
            throw new IllegalArgumentException("log query result counts must be consistent");
        }
    }

    public static LogQueryResult legacy(String content) {
        String value = Objects.requireNonNull(content, "content");
        int lines = value.isBlank() ? 0 : value.split("\\R", -1).length;
        return success(value, "legacy", "unknown", "unknown", lines, lines, false);
    }

    public static LogQueryResult success(String content, String dataSourceId,
                                         String environment, long matched,
                                         int returned, boolean truncated) {
        return success(content, "log-query", dataSourceId, environment,
                matched, returned, truncated);
    }

    public static LogQueryResult success(String content, String queryId,
                                         String dataSourceId, String environment,
                                         long matched, int returned, boolean truncated) {
        return new LogQueryResult(content, queryId, dataSourceId, environment, matched, returned,
                truncated, "SUCCEEDED", "", 0);
    }

    public LogQueryResult withScope(String scopedDataSourceId, String scopedEnvironment) {
        return new LogQueryResult(content, queryId,
                required(scopedDataSourceId, "dataSourceId"),
                required(scopedEnvironment, "environment"), matched, returned,
                truncated, backendStatus, errorCode, retryCount);
    }

    public LogQueryResult withRetryCount(int retries) {
        return new LogQueryResult(content, queryId, dataSourceId, environment,
                matched, returned, truncated, backendStatus, errorCode, retries);
    }

    public String legacyRender() {
        String header = "queryId=" + queryId
                + " matched=" + matched + " returned=" + returned
                + " truncated=" + truncated;
        return content.isBlank() ? header : header + "\n" + content;
    }

    private static String safeScope(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static String required(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
