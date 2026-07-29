package com.anthropic.agentkit.domain.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Immutable hard boundary of tool names available to one agent role. */
public final class ToolCapabilitySet {

    private static final ToolCapabilitySet NONE = new ToolCapabilitySet(Set.of());

    private final Set<String> names;

    private ToolCapabilitySet(Set<String> names) {
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String name : Objects.requireNonNull(names, "names")) {
            copy.add(requireName(name));
        }
        this.names = Collections.unmodifiableSet(copy);
    }

    public static ToolCapabilitySet none() {
        return NONE;
    }

    public static ToolCapabilitySet of(String... names) {
        Objects.requireNonNull(names, "names");
        return names.length == 0
                ? none()
                : new ToolCapabilitySet(new LinkedHashSet<>(Arrays.asList(names)));
    }

    public static ToolCapabilitySet copyOf(Set<String> names) {
        return names.isEmpty() ? none() : new ToolCapabilitySet(names);
    }

    public Set<String> names() {
        return names;
    }

    public boolean contains(String name) {
        return names.contains(name);
    }

    public boolean isSubsetOf(ToolCapabilitySet other) {
        return Objects.requireNonNull(other, "other").names.containsAll(names);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ToolCapabilitySet capabilities
                && names.equals(capabilities.names);
    }

    @Override
    public int hashCode() {
        return names.hashCode();
    }

    @Override
    public String toString() {
        return names.toString();
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool capability name must not be blank");
        }
        return name;
    }
}
