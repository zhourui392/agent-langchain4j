package com.anthropic.cclc.infrastructure.tools.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * {@link HttpReader} backed by the JDK {@code HttpClient}. Thin protocol adapter:
 * no business judgement here, so it is covered by integration rather than unit
 * tests.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class JdkHttpReader implements HttpReader {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public HttpResponseView get(String url, Map<String, String> headers, Duration timeout) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResponseView(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("http get interrupted", e);
        }
    }
}
