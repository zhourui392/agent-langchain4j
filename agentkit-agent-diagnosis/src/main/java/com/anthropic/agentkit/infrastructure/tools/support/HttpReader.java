package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.net.InetAddress;
import java.net.URI;

/**
 * Seam for read-only HTTP GET so {@code HttpGetTool} can be unit-tested without
 * hitting the network. The default {@link JdkHttpReader} talks to real endpoints.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public interface HttpReader {

    HttpResponseView get(String url, Map<String, String> headers, Duration timeout) throws IOException;

    /**
     * Executes against the exact address approved by the caller, preventing a second DNS lookup.
     * Implementations that do not support pinning retain source compatibility, but production
     * transports must override this method.
     */
    default HttpResponseView getPinned(URI target, InetAddress address,
                                       Map<String, String> headers,
                                       Duration timeout) throws IOException {
        return get(target.toString(), headers, timeout);
    }

    record HttpResponseView(int statusCode, String body) {
        public HttpResponseView {
            Objects.requireNonNull(body, "body");
        }
    }
}
