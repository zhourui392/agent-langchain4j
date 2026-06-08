package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.EsReadClient;

import java.util.Objects;

/**
 * Read-only Elasticsearch tool: search / count / get / mapping. Stub for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class EsReadTool implements Tool {

    private final EsReadClient client;

    public EsReadTool(EsReadClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return "EsRead";
    }

    @Override
    public String description() {
        return "Read-only Elasticsearch access: op=search|count|get|mapping against an index.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"op\":{\"type\":\"string\",\"enum\":[\"search\",\"count\",\"get\",\"mapping\"]},"
                + "\"index\":{\"type\":\"string\"},"
                + "\"query\":{\"type\":\"string\"},"
                + "\"id\":{\"type\":\"string\"},"
                + "\"size\":{\"type\":\"integer\"}},"
                + "\"required\":[\"op\",\"index\"]}";
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
