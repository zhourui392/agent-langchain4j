package com.anthropic.agentkit.domain.tool;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One atomically published generation from a dynamic tool source. */
public record ToolCatalogSnapshot(String source, List<Tool> tools) {

    public ToolCatalogSnapshot {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("catalog source must not be blank");
        }
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        requireUniqueNames(source, tools);
    }

    private static void requireUniqueNames(String source, List<Tool> tools) {
        Set<String> names = new HashSet<>();
        for (Tool tool : tools) {
            Objects.requireNonNull(tool, "tool");
            if (!names.add(tool.name())) {
                throw new IllegalStateException(
                        "duplicate tool in catalog " + source + ": " + tool.name());
            }
        }
    }
}
