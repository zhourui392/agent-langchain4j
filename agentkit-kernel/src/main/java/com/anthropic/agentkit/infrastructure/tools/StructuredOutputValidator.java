package com.anthropic.agentkit.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict validator for the JSON Schema subset supported by terminal tools. */
final class StructuredOutputValidator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SUPPORTED_KEYWORDS = Set.of(
            "$schema", "title", "description", "default", "type", "properties",
            "required", "additionalProperties", "items", "enum", "minItems", "minLength");

    private final JsonNode schema;

    StructuredOutputValidator(JsonNode schema) {
        this.schema = schema;
        validateSupportedSchema(schema, "$schema");
    }

    List<String> validate(Map<String, Object> values) {
        List<String> errors = new ArrayList<>();
        validateNode(JSON.valueToTree(values), schema, "", errors);
        return List.copyOf(errors);
    }

    private static void validateNode(JsonNode value, JsonNode schema,
                                     String path, List<String> errors) {
        validateEnum(value, schema, path, errors);
        String type = schema.path("type").asText("");
        if (!type.isEmpty() && !matchesType(value, type)) {
            errors.add(label(path) + " must be " + type);
            return;
        }
        if ("object".equals(type)) {
            validateObject(value, schema, path, errors);
        } else if ("array".equals(type)) {
            validateArray(value, schema, path, errors);
        } else if ("string".equals(type)) {
            validateString(value, schema, path, errors);
        }
    }

    private static void validateObject(JsonNode value, JsonNode schema,
                                       String path, List<String> errors) {
        JsonNode properties = schema.path("properties");
        validateRequired(value, schema.path("required"), path, errors);
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldPath = child(path, field.getKey());
            JsonNode fieldSchema = properties.get(field.getKey());
            if (fieldSchema != null) {
                validateNode(field.getValue(), fieldSchema, fieldPath, errors);
            } else if (!schema.path("additionalProperties").asBoolean(true)) {
                errors.add(fieldPath + " is not allowed");
            }
        }
    }

    private static void validateRequired(JsonNode value, JsonNode required,
                                         String path, List<String> errors) {
        if (!required.isArray()) {
            return;
        }
        required.forEach(field -> {
            String name = field.asText();
            if (!value.hasNonNull(name)) {
                errors.add(child(path, name) + " is required");
            }
        });
    }

    private static void validateArray(JsonNode value, JsonNode schema,
                                      String path, List<String> errors) {
        int minItems = schema.path("minItems").asInt(0);
        if (value.size() < minItems) {
            errors.add(label(path) + " must contain at least " + minItems + " item(s)");
        }
        JsonNode itemSchema = schema.path("items");
        if (itemSchema.isMissingNode()) {
            return;
        }
        for (int index = 0; index < value.size(); index++) {
            validateNode(value.get(index), itemSchema,
                    label(path) + "[" + index + "]", errors);
        }
    }

    private static void validateString(JsonNode value, JsonNode schema,
                                       String path, List<String> errors) {
        int minLength = schema.path("minLength").asInt(0);
        if (value.textValue().length() < minLength) {
            errors.add(label(path) + " must contain at least "
                    + minLength + " character(s)");
        }
    }

    private static void validateEnum(JsonNode value, JsonNode schema,
                                     String path, List<String> errors) {
        JsonNode allowed = schema.path("enum");
        if (!allowed.isArray()) {
            return;
        }
        for (JsonNode candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        errors.add(label(path) + " must be one of " + allowed);
    }

    private static boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> throw new IllegalArgumentException("unsupported schema type: " + type);
        };
    }

    private static void validateSupportedSchema(JsonNode schema, String path) {
        if (!schema.isObject()) {
            throw new IllegalArgumentException(path + " must be a schema object");
        }
        schema.fieldNames().forEachRemaining(keyword -> {
            if (!SUPPORTED_KEYWORDS.contains(keyword)) {
                throw new IllegalArgumentException("unsupported schema keyword: " + keyword);
            }
        });
        schema.path("properties").fields().forEachRemaining(
                field -> validateSupportedSchema(field.getValue(), path + "." + field.getKey()));
        JsonNode items = schema.path("items");
        if (!items.isMissingNode()) {
            validateSupportedSchema(items, path + ".items");
        }
    }

    private static String child(String path, String field) {
        return path == null || path.isEmpty() ? field : path + "." + field;
    }

    private static String label(String path) {
        return path == null || path.isEmpty() ? "payload" : path;
    }
}
