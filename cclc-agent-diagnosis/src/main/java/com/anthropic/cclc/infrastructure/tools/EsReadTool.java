package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.EsReadClient;

import java.io.IOException;
import java.util.Objects;

/**
 * Read-only Elasticsearch tool: search / count / get / mapping.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class EsReadTool implements Tool {

    private static final int DEFAULT_SIZE = 10;

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
        String op = args.getString("op", "").trim();
        String index = args.getString("index", "").trim();
        if (index.isEmpty()) {
            return ToolResult.error("EsRead requires 'index'");
        }
        try {
            return switch (op) {
                case "search" -> ToolResult.ok(
                        client.search(index, args.getString("query", "{}"), args.getInt("size", DEFAULT_SIZE)));
                case "count" -> ToolResult.ok("count: " + client.count(index, args.getString("query", "{}")));
                case "get" -> ToolResult.ok(client.get(index, args.getString("id", "")));
                case "mapping" -> ToolResult.ok(client.mapping(index));
                default -> ToolResult.error("EsRead unknown op: '" + op + "' (use search|count|get|mapping)");
            };
        } catch (IOException ex) {
            return ToolResult.error("EsRead failed: " + ex.getMessage());
        }
    }
}
