package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.RedisReadClient;

import java.util.Objects;

/**
 * Read-only Redis tool. An allowlist of read commands (GET/TTL/TYPE/SCAN/...) is
 * the enforcement: anything not on it is rejected (default-deny). Stub for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class RedisReadTool implements Tool {

    private final RedisReadClient client;

    public RedisReadTool(RedisReadClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return "RedisRead";
    }

    @Override
    public String description() {
        return "Read-only Redis: run one read command (GET/MGET/TYPE/TTL/SCAN/HGETALL/LRANGE/...).";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"command\":{\"type\":\"string\"},"
                + "\"timeoutMs\":{\"type\":\"integer\"}},"
                + "\"required\":[\"command\"]}";
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
