package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.HttpReader;
import com.anthropic.cclc.infrastructure.tools.support.HttpReader.HttpResponseView;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only HTTP GET for verifying endpoints during diagnosis.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class HttpGetTool implements Tool {

    private static final int DEFAULT_TIMEOUT_MS = 15_000;

    private final HttpReader httpReader;

    public HttpGetTool(HttpReader httpReader) {
        this.httpReader = Objects.requireNonNull(httpReader, "httpReader");
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
        String url = args.getString("url", "").trim();
        if (!isHttpUrl(url)) {
            return ToolResult.error("HttpGet requires an http(s) url, got: " + url);
        }
        Duration timeout = Duration.ofMillis(args.getInt("timeoutMs", DEFAULT_TIMEOUT_MS));
        try {
            HttpResponseView response = httpReader.get(url, headers(args), timeout);
            return ToolResult.ok("HTTP " + response.statusCode() + "\n" + response.body());
        } catch (IOException ex) {
            return ToolResult.error("HttpGet failed: " + ex.getMessage());
        }
    }

    private static boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
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
}
