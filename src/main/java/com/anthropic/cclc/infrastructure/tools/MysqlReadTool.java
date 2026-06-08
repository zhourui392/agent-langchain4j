package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.MysqlReadClient;

import java.util.Objects;

/**
 * Read-only MySQL tool: runs a single read statement and returns the rows.
 * A SQL guard rejects anything that is not a plain read. Stub for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class MysqlReadTool implements Tool {

    private final MysqlReadClient client;

    public MysqlReadTool(MysqlReadClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return "MysqlRead";
    }

    @Override
    public String description() {
        return "Read-only MySQL: run one SELECT/WITH/SHOW/DESCRIBE/EXPLAIN statement; returns rows.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"sql\":{\"type\":\"string\"},"
                + "\"maxRows\":{\"type\":\"integer\"}},"
                + "\"required\":[\"sql\"]}";
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
