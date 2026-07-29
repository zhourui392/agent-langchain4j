package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.domain.port.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
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
        if (!root.isObject()) {
            throw new IllegalArgumentException("tool input schema must be a JSON object");
        }
        return toObjectSchema(root);
    }

    private static JsonObjectSchema toObjectSchema(JsonNode root) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        builder.description(root.path("description").asText(null));
        addProperties(builder, root.path("properties"));
        addRequired(builder, root.path("required"));
        if (root.path("additionalProperties").isBoolean()) {
            builder.additionalProperties(root.path("additionalProperties").asBoolean());
        }
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
        if (propertySchema.path("anyOf").isArray()) {
            List<JsonSchemaElement> alternatives = new ArrayList<>();
            propertySchema.path("anyOf").forEach(item ->
                    alternatives.add(toJsonSchemaElement(item)));
            return JsonAnyOfSchema.builder()
                    .description(propertySchema.path("description").asText(null))
                    .anyOf(alternatives).build();
        }
        String type = propertySchema.path("type").asText("string");
        String description = propertySchema.path("description").asText(null);
        return switch (type) {
            case "string" -> stringSchema(propertySchema, description);
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array" -> JsonArraySchema.builder().description(description)
                    .items(toJsonSchemaElement(propertySchema.path("items"))).build();
            case "object" -> toObjectSchema(propertySchema);
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    private static JsonSchemaElement stringSchema(JsonNode schema, String description) {
        if (!schema.path("enum").isArray()) {
            return JsonStringSchema.builder().description(description).build();
        }
        List<String> values = new ArrayList<>();
        schema.path("enum").forEach(value -> values.add(value.asText()));
        return JsonEnumSchema.builder().description(description).enumValues(values).build();
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
