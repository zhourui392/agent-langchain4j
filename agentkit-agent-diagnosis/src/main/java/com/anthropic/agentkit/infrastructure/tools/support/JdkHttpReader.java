package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link HttpReader} backed by the JDK {@code HttpClient}. Thin protocol adapter:
 * no business judgement here, so it is covered by integration rather than unit
 * tests.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class JdkHttpReader implements HttpReader {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpReader.class);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public HttpResponseView get(String url, Map<String, String> headers, Duration timeout) throws IOException {
        long startNs = System.nanoTime();
        log.debug("jdk http get started: url={}, headers={}, timeoutMs={}",
                LogSanitizer.stripQuery(url), headers.keySet(), timeout.toMillis());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            log.debug("jdk http get completed: url={}, status={}, bytes={}, durationMs={}",
                    LogSanitizer.stripQuery(url), response.statusCode(),
                    response.body().getBytes(StandardCharsets.UTF_8).length,
                    elapsedMs(startNs));
            return new HttpResponseView(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("jdk http get interrupted: url={}", LogSanitizer.stripQuery(url), e);
            throw new IOException("http get interrupted", e);
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
