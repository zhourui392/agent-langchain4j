package com.anthropic.agentkit.infrastructure.mcp;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable collection copies that preserve MCP declaration and discovery order. */
final class McpDeclarationOrder {

    private McpDeclarationOrder() { }

    static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        Objects.requireNonNull(source, "source");
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    static <E> Set<E> immutableSet(Collection<E> source) {
        Objects.requireNonNull(source, "source");
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
