package com.anthropic.cclc.domain.tool;

import com.anthropic.cclc.domain.port.ToolSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ToolRegistry {

    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        String name = tool.name();
        if (byName.containsKey(name)) {
            throw new IllegalStateException("tool already registered: " + name);
        }
        byName.put(name, tool);
        return this;
    }

    public Tool find(String name) {
        Tool t = byName.get(name);
        if (t == null) {
            throw new UnknownToolException(name);
        }
        return t;
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
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
}
