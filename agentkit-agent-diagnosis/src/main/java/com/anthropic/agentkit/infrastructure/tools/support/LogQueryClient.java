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
}
