package com.anthropic.agentkit.domain.tool;

import com.anthropic.agentkit.domain.port.ToolSpec;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable run-scoped projection resolved once from static and dynamic tools.
 *
 * @author alex
 */
public record ToolRegistrySnapshot(long generation, List<Tool> tools) {

    public ToolRegistrySnapshot {
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        Set<String> names = new LinkedHashSet<>();
        for (Tool tool : tools) {
            Objects.requireNonNull(tool, "tool");
            if (!names.add(tool.name())) {
                throw new IllegalStateException("duplicate tool in registry snapshot: " + tool.name());
            }
        }
    }

    public Set<String> names() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        tools.forEach(tool -> names.add(tool.name()));
        return Collections.unmodifiableSet(names);
    }

    public List<ToolSpec> specs() {
        return tools.stream().map(tool -> new ToolSpec(
                tool.name(), tool.description(), tool.inputSchema())).toList();
    }

    public ToolRegistry frozenRegistry() {
        ToolRegistry registry = new ToolRegistry();
        tools.forEach(registry::register);
        return registry;
    }
}
