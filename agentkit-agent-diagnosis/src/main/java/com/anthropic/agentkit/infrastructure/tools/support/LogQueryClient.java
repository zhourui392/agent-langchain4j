package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;

/**
 * Log search seam implemented by the host service or a platform adapter.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@FunctionalInterface
public interface LogQueryClient {

    String query(LogQueryRequest request) throws IOException;

    default LogQueryResult queryResult(LogQueryRequest request) throws IOException {
        return LogQueryResult.legacy(query(request));
    }

    default String dataSourceId() {
        return "unknown";
    }

    default String environment() {
        return "unknown";
    }

    default String service() {
        return "unknown";
    }
}
