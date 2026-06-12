package com.anthropic.cclc.infrastructure.llm;

import com.anthropic.cclc.domain.port.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class ToolSpecificationMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolSpecificationMapper() {
    }

    static ToolSpecification toLc(ToolSpec spec) {
        return ToolSpecification.builder()
                .name(spec.name())
                .description(spec.description())
                .parameters(parseObjectSchema(spec.inputSchema()))
                .build();
    }

    private static JsonObjectSchema parseObjectSchema(String inputSchema) {
        JsonNode root = readJson(inputSchema);
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        addProperties(builder, root.path("properties"));
        addRequired(builder, root.path("required"));
        return builder.build();
    }

    private static void addProperties(JsonObjectSchema.Builder builder, JsonNode propertiesNode) {
        if (!propertiesNode.isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = propertiesNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            builder.addProperty(field.getKey(), toJsonSchemaElement(field.getValue()));
        }
    }

    private static void addRequired(JsonObjectSchema.Builder builder, JsonNode requiredNode) {
        if (!requiredNode.isArray() || requiredNode.isEmpty()) {
            return;
        }
        List<String> required = new ArrayList<>();
        requiredNode.forEach(n -> required.add(n.asText()));
        builder.required(required);
    }

    private static JsonSchemaElement toJsonSchemaElement(JsonNode propertySchema) {
        String type = propertySchema.path("type").asText("string");
        String description = propertySchema.path("description").asText(null);
        return switch (type) {
            case "string" -> JsonStringSchema.builder().description(description).build();
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    private static JsonNode readJson(String inputSchema) {
        try {
            return JSON.readTree(inputSchema);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "invalid JSON schema string: " + inputSchema, e);
        }
    }
}
