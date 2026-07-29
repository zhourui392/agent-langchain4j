package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.tool.Tool;

import java.util.List;
import java.util.Set;

/** One immutable, atomically publishable generation of a scoped MCP catalog. */
record McpCatalogGeneration(
        List<McpToolDescriptor> descriptors,
        Set<String> selected,
        List<Tool> tools) {

    McpCatalogGeneration {
        descriptors = List.copyOf(descriptors);
        selected = McpDeclarationOrder.immutableSet(selected);
        tools = List.copyOf(tools);
    }
}
