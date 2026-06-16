package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Seam for read-only HTTP GET so {@code HttpGetTool} can be unit-tested without
 * hitting the network. The default {@link JdkHttpReader} talks to real endpoints.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public interface HttpReader {

    HttpResponseView get(String url, Map<String, String> headers, Duration timeout) throws IOException;

    record HttpResponseView(int statusCode, String body) {
        public HttpResponseView {
            Objects.requireNonNull(body, "body");
        }
    }
}
