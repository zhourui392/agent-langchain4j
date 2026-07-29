package com.anthropic.agentkit.domain.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Immutable description of ordinary and terminal tool boundaries. */
public record CapabilityDescriptor(
        ToolCapabilitySet allowedTools,
        Set<String> terminalTools) {

    private static final CapabilityDescriptor NONE = new CapabilityDescriptor(
            ToolCapabilitySet.none(), Set.of());

    public CapabilityDescriptor {
        Objects.requireNonNull(allowedTools, "allowedTools");
        terminalTools = immutableNames(terminalTools);
        rejectCollisions(allowedTools, terminalTools);
    }

    public static CapabilityDescriptor none() {
        return NONE;
    }

    private static Set<String> immutableNames(Set<String> names) {
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String name : Objects.requireNonNull(names, "terminalTools")) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("terminal tool name must not be blank");
            }
            copy.add(name);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static void rejectCollisions(ToolCapabilitySet tools, Set<String> terminals) {
        for (String terminal : terminals) {
            if (tools.contains(terminal)) {
                throw new IllegalArgumentException(
                        "terminal tool must not duplicate an allowed tool: " + terminal);
            }
        }
    }
}
