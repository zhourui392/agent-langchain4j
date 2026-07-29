package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Catalog validation, selection, and eager/deferred exposure policy for one server. */
final class McpCatalogPolicy {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String serverId;
    private final int eagerToolLimit;

    McpCatalogPolicy(String serverId, int eagerToolLimit) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.eagerToolLimit = eagerToolLimit;
    }

    List<McpToolDescriptor> validate(List<McpToolDescriptor> discovered) {
        Objects.requireNonNull(discovered, "MCP tool catalog");
        List<McpToolDescriptor> descriptors = List.copyOf(discovered);
        Set<String> names = new LinkedHashSet<>();
        for (McpToolDescriptor descriptor : descriptors) {
            Objects.requireNonNull(descriptor, "MCP tool descriptor");
            validateSchema(descriptor);
            if (McpDiscoverTool.RAW_NAME.equals(descriptor.name())
                    || !names.add(descriptor.name())) {
                throw new McpProtocolException(
                        "duplicate or reserved MCP tool name: " + descriptor.name());
            }
        }
        return descriptors;
    }

    Set<String> mergeSelection(
            Set<String> current, List<McpToolDescriptor> descriptors,
            Collection<String> requested) {
        Set<String> selected = new LinkedHashSet<>(current);
        selected.addAll(validateSelection(descriptors, requested));
        return McpDeclarationOrder.immutableSet(selected);
    }

    Set<String> selectionForRefresh(
            McpCatalogGeneration previous, List<McpToolDescriptor> descriptors) {
        if (previous == null) {
            return Set.of();
        }
        Set<String> available = namesOf(descriptors);
        List<String> retained = previous.selected().stream()
                .filter(available::contains).toList();
        return McpDeclarationOrder.immutableSet(retained);
    }

    Set<String> effectiveSelection(
            List<McpToolDescriptor> descriptors, Set<String> selected) {
        return descriptors.size() > eagerToolLimit
                ? McpDeclarationOrder.immutableSet(selected) : namesOf(descriptors);
    }

    boolean deferred(List<McpToolDescriptor> descriptors) {
        return descriptors.size() > eagerToolLimit;
    }

    Set<String> resolveDiscovery(
            List<McpToolDescriptor> descriptors, ToolArguments arguments) {
        Set<String> names = requestedNames(arguments);
        return names.isEmpty() ? queryMatches(descriptors, arguments) : names;
    }

    String discoveryJson(Set<String> selected) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("server", serverId);
            value.put("exposed", selected.stream()
                    .map(name -> serverId + "." + name).toList());
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new McpProtocolException("failed to encode discovery result", failure);
        }
    }

    private static Set<String> validateSelection(
            List<McpToolDescriptor> descriptors, Collection<String> requested) {
        Objects.requireNonNull(requested, "rawNames");
        Set<String> available = namesOf(descriptors);
        Set<String> selected = new LinkedHashSet<>();
        for (String name : requested) {
            if (!available.contains(name)) {
                throw new IllegalArgumentException("unknown MCP tool selection: " + name);
            }
            selected.add(name);
        }
        return selected;
    }

    private static Set<String> requestedNames(ToolArguments arguments) {
        Object value = arguments.values().get("names");
        if (!(value instanceof Collection<?> values)) {
            return Set.of();
        }
        List<String> names = new ArrayList<>();
        values.forEach(item -> names.add(String.valueOf(item)));
        return McpDeclarationOrder.immutableSet(names);
    }

    private static Set<String> queryMatches(
            List<McpToolDescriptor> descriptors, ToolArguments arguments) {
        String query = arguments.getString("query", "").toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(arguments.getInt("limit", 8), 32));
        if (query.isBlank()) {
            throw new IllegalArgumentException("names or query is required");
        }
        List<String> matches = descriptors.stream()
                .filter(descriptor -> matches(descriptor, query))
                .limit(limit).map(McpToolDescriptor::name).toList();
        return McpDeclarationOrder.immutableSet(matches);
    }

    private static boolean matches(McpToolDescriptor descriptor, String query) {
        return descriptor.name().toLowerCase(Locale.ROOT).contains(query)
                || descriptor.description().toLowerCase(Locale.ROOT).contains(query);
    }

    private static Set<String> namesOf(List<McpToolDescriptor> descriptors) {
        return McpDeclarationOrder.immutableSet(
                descriptors.stream().map(McpToolDescriptor::name).toList());
    }

    private static void validateSchema(McpToolDescriptor descriptor) {
        try {
            JsonNode schema = JSON.readTree(descriptor.inputSchema());
            if (!isObjectSchema(schema)) {
                throw new McpProtocolException(
                        "MCP tool schema must be an object: " + descriptor.name());
            }
        } catch (McpProtocolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new McpProtocolException(
                    "invalid MCP tool schema: " + descriptor.name(), failure);
        }
    }

    private static boolean isObjectSchema(JsonNode schema) {
        return schema != null && schema.isObject()
                && (schema.path("type").isMissingNode()
                || "object".equals(schema.path("type").asText()));
    }
}
