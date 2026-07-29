package com.anthropic.agentkit.domain.tool;

import com.anthropic.agentkit.domain.port.ToolSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ToolRegistry {

    private final Map<String, Tool> byName = new LinkedHashMap<>();
    private final List<ToolCatalog> catalogs = new ArrayList<>();

    public ToolRegistry register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        String name = tool.name();
        if (byName.containsKey(name)) {
            throw new IllegalStateException("tool already registered: " + name);
        }
        byName.put(name, tool);
        return this;
    }

    public ToolRegistry registerCatalog(ToolCatalog catalog) {
        catalogs.add(Objects.requireNonNull(catalog, "catalog"));
        return this;
    }

    public Tool find(String name) {
        Tool t = byName.get(name);
        if (t == null) {
            throw new UnknownToolException(name);
        }
        return t;
    }

    public Tool find(String name, ExecutionContext context) {
        Tool tool = resolved(context).get(name);
        if (tool == null) {
            throw new UnknownToolException(name);
        }
        return tool;
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    public boolean contains(String name, ExecutionContext context) {
        return resolved(context).containsKey(name);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(byName.keySet());
    }

    public List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>(byName.size());
        for (Tool tool : byName.values()) {
            specs.add(new ToolSpec(tool.name(), tool.description(), tool.inputSchema()));
        }
        return Collections.unmodifiableList(specs);
    }

    public List<ToolSpec> specs(ExecutionContext context) {
        List<ToolSpec> specs = new ArrayList<>();
        resolved(context).values().forEach(tool -> specs.add(
                new ToolSpec(tool.name(), tool.description(), tool.inputSchema())));
        return List.copyOf(specs);
    }

    private Map<String, Tool> resolved(ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, Tool> resolved = new LinkedHashMap<>(byName);
        for (ToolCatalog catalog : catalogs) {
            ToolCatalogSnapshot snapshot = Objects.requireNonNull(
                    catalog.snapshot(context), "tool catalog returned null snapshot");
            merge(resolved, snapshot);
        }
        return resolved;
    }

    private static void merge(Map<String, Tool> resolved, ToolCatalogSnapshot snapshot) {
        for (Tool tool : snapshot.tools()) {
            if (resolved.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("tool namespace collision from catalog "
                        + snapshot.source() + ": " + tool.name());
            }
        }
    }
}
