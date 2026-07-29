package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolKind;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal tool used by specialized agents to submit schema-bound output.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class StructuredOutputTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final String description;
    private final String inputSchema;
    private final StructuredOutputValidator validator;
    private final Consumer<Map<String, Object>> sink;

    public StructuredOutputTool(String name, String description, String inputSchema,
                                Consumer<Map<String, Object>> sink) {
        this.name = requireText(name, "name");
        this.description = requireText(description, "description");
        this.inputSchema = requireObjectSchema(inputSchema);
        this.validator = new StructuredOutputValidator(readSchema(inputSchema));
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
    public ToolKind kind() {
        return ToolKind.TERMINAL;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        log.debug("structured output args: name={}, fields={}", name, args.values().keySet());
        List<String> errors = validator.validate(args.values());
        if (!errors.isEmpty()) {
            log.debug("structured output validation failed: name={}, errors={}", name, errors.size());
            return ToolResult.error("invalid structured output: " + String.join("; ", errors));
        }
        sink.accept(Map.copyOf(args.values()));
        log.debug("structured output validation passed: name={}, fields={}", name, args.values().keySet());
        return ToolResult.ok("structured output accepted: " + name);
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

    private static JsonNode readSchema(String inputSchema) {
        try {
            return JSON.readTree(Objects.requireNonNull(inputSchema, "inputSchema"));
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid structured output schema", ex);
        }
    }
}
