package com.anthropic.agentkit.infrastructure.tools.support;

import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed, host-created HTTP health probe that never publishes endpoint or response data.
 *
 * @author alex
 * @since 2026-07-30
 */
public final class HttpBackendHealthProbe implements BackendHealthProbe {

    private final URI endpoint;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient client;
    private final Clock clock;

    public HttpBackendHealthProbe(String endpoint, Map<String, String> headers,
                                  Duration timeout, HttpClient client) {
        this(endpoint, headers, timeout, client, Clock.systemUTC());
    }

    HttpBackendHealthProbe(String endpoint, Map<String, String> headers,
                           Duration timeout, HttpClient client, Clock clock) {
        this.endpoint = requireEndpoint(endpoint);
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("health timeout must be positive");
        }
        this.client = requireSafeClient(client);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public BackendHealth probe() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout).GET();
        headers.forEach(builder::header);
        try {
            HttpResponse<InputStream> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream ignored = response.body()) {
                return status(response.statusCode());
            }
        } catch (HttpTimeoutException failure) {
            return health(ReadinessStatus.DEGRADED, "BACKEND_TIMED_OUT");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return health(ReadinessStatus.UNAVAILABLE, "BACKEND_PROBE_INTERRUPTED");
        } catch (IOException failure) {
            return health(ReadinessStatus.DEGRADED, "BACKEND_CONNECTION_FAILED");
        }
    }

    private BackendHealth status(int status) {
        if (status >= 200 && status < 300) {
            return health(ReadinessStatus.READY, "BACKEND_READY");
        }
        if (status == 401) {
            return health(ReadinessStatus.UNAVAILABLE, "BACKEND_AUTHENTICATION_FAILED");
        }
        if (status == 403) {
            return health(ReadinessStatus.UNAVAILABLE, "BACKEND_AUTHORIZATION_DENIED");
        }
        if (status == 408 || status == 429 || status >= 500) {
            String reason = status == 408 ? "BACKEND_TIMED_OUT"
                    : status == 429 ? "BACKEND_RATE_LIMITED" : "BACKEND_UNAVAILABLE";
            return health(ReadinessStatus.DEGRADED, reason);
        }
        return health(ReadinessStatus.UNAVAILABLE, "BACKEND_HEALTH_REJECTED");
    }

    private BackendHealth health(ReadinessStatus status, String reason) {
        return new BackendHealth(status, reason, clock.instant());
    }

    private static URI requireEndpoint(String value) {
        URI uri = URI.create(Objects.requireNonNull(value, "endpoint").trim());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("health endpoint must be a safe HTTP(S) URI");
        }
        return uri;
    }

    private static HttpClient requireSafeClient(HttpClient client) {
        HttpClient value = Objects.requireNonNull(client, "client");
        if (value.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("backend health redirects must be disabled");
        }
        return value;
    }
}
