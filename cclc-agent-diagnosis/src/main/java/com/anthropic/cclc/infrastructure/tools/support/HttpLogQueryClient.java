package com.anthropic.cclc.infrastructure.tools.support;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LogQueryClient} backed by a host-provided HTTP endpoint.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class HttpLogQueryClient implements LogQueryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLogQueryClient.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String endpointUrl;
    private final Map<String, String> headers;
    private final HttpClient client = HttpClient.newHttpClient();

    public HttpLogQueryClient(String endpointUrl, Map<String, String> headers) {
        this.endpointUrl = Objects.requireNonNull(endpointUrl, "endpointUrl");
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public String query(LogQueryRequest request) throws IOException {
        long startNs = System.nanoTime();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(request)).timeout(TIMEOUT).GET();
        headers.forEach(builder::header);
        log.debug("log http query started: endpoint={}, traceIdPresent={}, service={}, limit={}",
                LogSanitizer.stripQuery(endpointUrl), !request.traceId().isBlank(),
                request.service(), request.limit());
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            log.debug("log http query completed: endpoint={}, status={}, bytes={}, durationMs={}",
                    LogSanitizer.stripQuery(endpointUrl), response.statusCode(),
                    response.body().getBytes(StandardCharsets.UTF_8).length, elapsedMs(startNs));
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("log http query interrupted: endpoint={}", LogSanitizer.stripQuery(endpointUrl), ex);
            throw new IOException("log query interrupted", ex);
        }
    }

    private URI uri(LogQueryRequest request) {
        String query = queryString(parameters(request));
        String separator = endpointUrl.contains("?") ? "&" : "?";
        return URI.create(endpointUrl + separator + query);
    }

    private Map<String, String> parameters(LogQueryRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        putIfPresent(params, "traceId", request.traceId());
        putIfPresent(params, "keyword", request.keyword());
        putIfPresent(params, "service", request.service());
        putIfPresent(params, "startTime", request.startTime());
        putIfPresent(params, "endTime", request.endTime());
        putIfPresent(params, "level", request.level());
        params.put("limit", Integer.toString(request.limit()));
        return params;
    }

    private static void putIfPresent(Map<String, String> params, String name, String value) {
        if (!value.isBlank()) {
            params.put(name, value);
        }
    }

    private static String queryString(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        params.forEach((key, value) -> joiner.add(encode(key) + "=" + encode(value)));
        return joiner.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
