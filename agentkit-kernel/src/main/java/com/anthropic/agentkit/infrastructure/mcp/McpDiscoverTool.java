package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;

import java.util.Objects;

final class McpDiscoverTool implements Tool {

    static final String RAW_NAME = "__discover_tools";
    private static final String SCHEMA = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"Name or description search"},
              "names":{"type":"array","items":{"type":"string"},
                       "description":"Exact remote tool names to expose"},
              "limit":{"type":"integer","description":"Maximum query matches"}},
             "additionalProperties":false}
            """;

    private final String serverId;
    private final CatalogSelector selector;

    McpDiscoverTool(String serverId, CatalogSelector selector) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.selector = Objects.requireNonNull(selector, "selector");
    }

    @Override public String name() { return serverId + "." + RAW_NAME; }

    @Override
    public String description() {
        return "Search and expose selected tool schemas from MCP server " + serverId;
    }

    @Override public String inputSchema() { return SCHEMA; }

    @Override public boolean isReadOnly() { return true; }

    @Override
    public ToolResult execute(ToolArguments arguments, ExecutionContext context) {
        try {
            return ToolResult.ok(selector.select(arguments, context));
        } catch (IllegalArgumentException | McpProtocolException failure) {
            return ToolResult.error("MCP tool discovery failed: " + failure.getMessage());
        }
    }

    @FunctionalInterface
    interface CatalogSelector {
        String select(ToolArguments arguments, ExecutionContext context);
    }
}
