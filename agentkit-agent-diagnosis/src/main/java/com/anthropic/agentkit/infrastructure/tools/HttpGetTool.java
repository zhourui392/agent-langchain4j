package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.LogSanitizer;
import com.anthropic.agentkit.infrastructure.tools.support.HttpReader;
import com.anthropic.agentkit.infrastructure.tools.support.HttpReader.HttpResponseView;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only HTTP GET for verifying endpoints during diagnosis.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class HttpGetTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(HttpGetTool.class);

    private static final int DEFAULT_TIMEOUT_MS = 15_000;

    private final HttpReader httpReader;
    private final Set<String> allowedHosts;

    public HttpGetTool(HttpReader httpReader) {
        this(httpReader, Set.of());
    }

    public HttpGetTool(HttpReader httpReader, Set<String> allowedHosts) {
        this.httpReader = Objects.requireNonNull(httpReader, "httpReader");
        this.allowedHosts = normalizeHosts(allowedHosts);
    }

    @Override
    public String name() {
        return "HttpGet";
    }

    @Override
    public String description() {
        return "Issue a read-only HTTP GET to verify an endpoint; returns status and body.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"url\":{\"type\":\"string\"},"
                + "\"headers\":{\"type\":\"object\"},"
                + "\"timeoutMs\":{\"type\":\"integer\"}},"
                + "\"required\":[\"url\"]}";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        String url = args.getString("url", "").trim();
        if (!isHttpUrl(url)) {
            log.warn("http get blocked: reason=invalid_url, url={}", LogSanitizer.stripQuery(url));
            return ToolResult.error("HttpGet requires an http(s) url, got: " + url);
        }
        if (!isAllowedHost(url)) {
            log.warn("http get blocked: reason=host_not_allowlisted, host={}", host(url));
            return ToolResult.error("HttpGet host is not allowlisted: " + host(url));
        }
        Duration timeout = Duration.ofMillis(args.getInt("timeoutMs", DEFAULT_TIMEOUT_MS));
        log.debug("http get args: url={}, headers={}, timeoutMs={}",
                LogSanitizer.stripQuery(url), headers(args).keySet(), timeout.toMillis());
        try {
            HttpResponseView response = httpReader.get(url, headers(args), timeout);
            log.info("http get completed: url={}, status={}, responseBytes={}, durationMs={}",
                    LogSanitizer.stripQuery(url), response.statusCode(),
                    response.body().getBytes(StandardCharsets.UTF_8).length,
                    elapsedMs(startNs));
            return ToolResult.ok("HTTP " + response.statusCode() + "\n" + response.body());
        } catch (IOException ex) {
            log.error("http get failed: url={}", LogSanitizer.stripQuery(url), ex);
            return ToolResult.error("HttpGet failed: " + ex.getMessage());
        }
    }

    private static boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private boolean isAllowedHost(String url) {
        return allowedHosts.isEmpty() || allowedHosts.contains(host(url));
    }

    private static String host(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (URISyntaxException ex) {
            return "";
        }
    }

    private static Set<String> normalizeHosts(Set<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return Set.of();
        }
        return hosts.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(host -> !host.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, String> headers(ToolArguments args) {
        Object raw = args.values().get("headers");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> headers.put(String.valueOf(key), String.valueOf(value)));
        return headers;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
