package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Internal tool used by specialized agents to submit schema-bound output.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class StructuredOutputTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final String description;
    private final String inputSchema;
    private final List<String> requiredFields;
    private final Consumer<Map<String, Object>> sink;

    public StructuredOutputTool(String name, String description, String inputSchema,
                                Consumer<Map<String, Object>> sink) {
        this.name = requireText(name, "name");
        this.description = requireText(description, "description");
        this.inputSchema = requireObjectSchema(inputSchema);
        this.requiredFields = requiredFields(inputSchema);
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String inputSchema() {
        return inputSchema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        String missingField = firstMissingRequiredField(args.values());
        if (missingField != null) {
            return ToolResult.error("missing structured output field: " + missingField);
        }
        sink.accept(Map.copyOf(args.values()));
        return ToolResult.ok("structured output accepted: " + name);
    }

    private String firstMissingRequiredField(Map<String, Object> values) {
        for (String field : requiredFields) {
            if (!values.containsKey(field) || values.get(field) == null) {
                return field;
            }
        }
        return null;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String requireObjectSchema(String inputSchema) {
        JsonNode root = readSchema(inputSchema);
        if (!root.isObject() || !"object".equals(root.path("type").asText())) {
            throw new IllegalArgumentException("structured output schema must be a JSON object schema");
        }
        return inputSchema;
    }

    private static List<String> requiredFields(String inputSchema) {
        JsonNode required = readSchema(inputSchema).path("required");
        if (!required.isArray()) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        required.forEach(node -> fields.add(node.asText()));
        return List.copyOf(fields);
    }

    private static JsonNode readSchema(String inputSchema) {
        try {
            return JSON.readTree(Objects.requireNonNull(inputSchema, "inputSchema"));
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid structured output schema", ex);
        }
    }
}
