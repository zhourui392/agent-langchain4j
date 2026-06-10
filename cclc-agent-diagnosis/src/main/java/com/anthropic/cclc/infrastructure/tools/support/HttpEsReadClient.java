package com.anthropic.cclc.infrastructure.tools.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * {@link EsReadClient} over the ES REST API using the JDK {@code HttpClient} — no
 * heavyweight ES client on the classpath (keeps transitive deps light for the
 * in-process host). Thin protocol adapter; covered by integration, not unit, tests.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class HttpEsReadClient implements EsReadClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpEsReadClient(String baseUrl) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    @Override
    public String search(String index, String queryJson, int size) throws IOException {
        return post(endpoint(index) + "/_search?size=" + size, bodyOrEmpty(queryJson));
    }

    @Override
    public long count(String index, String queryJson) throws IOException {
        String response = post(endpoint(index) + "/_count", bodyOrEmpty(queryJson));
        JsonNode count = mapper.readTree(response).get("count");
        return count == null ? 0L : count.asLong();
    }

    @Override
    public String get(String index, String id) throws IOException {
        return get(endpoint(index) + "/_doc/" + encode(id));
    }

    @Override
    public String mapping(String index) throws IOException {
        return get(endpoint(index) + "/_mapping");
    }

    private String endpoint(String index) {
        return baseUrl + "/" + encode(index);
    }

    private String get(String url) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build());
    }

    private String post(String url, String body) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    private String send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("es request interrupted", e);
        }
    }

    private static String bodyOrEmpty(String queryJson) {
        return queryJson == null || queryJson.isBlank() ? "{}" : queryJson;
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
