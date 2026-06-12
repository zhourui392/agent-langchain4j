package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.LogQueryClient;
import com.anthropic.cclc.infrastructure.tools.support.LogQueryRequest;

import java.io.IOException;
import java.util.Objects;

/**
 * Read-only log search tool for incident diagnosis.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class LogQueryTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;

    private final LogQueryClient client;

    public LogQueryTool(LogQueryClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return "LogQuery";
    }

    @Override
    public String description() {
        return "Read-only log search by traceId or keyword with optional service and time window scope.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"traceId\":{\"type\":\"string\"},"
                + "\"keyword\":{\"type\":\"string\"},"
                + "\"service\":{\"type\":\"string\"},"
                + "\"startTime\":{\"type\":\"string\"},"
                + "\"endTime\":{\"type\":\"string\"},"
                + "\"level\":{\"type\":\"string\"},"
                + "\"limit\":{\"type\":\"integer\"}}}";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        LogQueryRequest request = request(args);
        if (!request.hasQueryAnchor()) {
            return ToolResult.error("LogQuery requires traceId or keyword");
        }
        try {
            return ToolResult.ok(client.query(request));
        } catch (IOException ex) {
            return ToolResult.error("LogQuery failed: " + ex.getMessage());
        }
    }

    private static LogQueryRequest request(ToolArguments args) {
        return new LogQueryRequest(
                args.getString("traceId", ""),
                args.getString("keyword", ""),
                args.getString("service", ""),
                args.getString("startTime", ""),
                args.getString("endTime", ""),
                args.getString("level", ""),
                args.getInt("limit", DEFAULT_LIMIT));
    }
}
