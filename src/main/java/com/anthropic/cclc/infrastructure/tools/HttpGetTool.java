package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.HttpReader;

import java.util.Objects;

/**
 * Read-only HTTP GET for verifying endpoints during diagnosis. Stub for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class HttpGetTool implements Tool {

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
        return ToolResult.error("not implemented");
    }
}
