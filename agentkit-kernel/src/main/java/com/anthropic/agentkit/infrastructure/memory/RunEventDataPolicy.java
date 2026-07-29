package com.anthropic.agentkit.infrastructure.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Governs sensitive and unbounded values immediately before an event reaches durable storage. */
final class RunEventDataPolicy {

    static final int MAX_TEXT_CHARACTERS = 32_000;
    static final String REDACTED = "[REDACTED]";

    private static final String TRUNCATION_MARKER =
            "...[agentkit: persisted value truncated; artifact unavailable]";
    private static final String PERSISTENCE_PREFIX = "agentkit.persistence.";
    private static final Set<String> STRUCTURAL_TEXT_FIELDS = Set.of(
            "type", "runId", "sessionId", "workspaceId", "occurredAt",
            "stopReason", "status", "toolUseId", "toolName");
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|"
                    + "authorization|credential)\\b\\s*[:=]\\s*)(?:bearer\\s+)?([^\\s,;&]+)");

    private final ObjectMapper json;

    RunEventDataPolicy(ObjectMapper json) {
        this.json = json;
    }

    ObjectNode govern(ObjectNode event) {
        return (ObjectNode) sanitize(event, "");
    }

    private JsonNode sanitize(JsonNode source, String fieldName) {
        if (isSensitive(fieldName)) {
            return TextNode.valueOf(REDACTED);
        }
        if (source.isObject()) {
            return sanitizeObject((ObjectNode) source);
        }
        if (source.isArray()) {
            var result = json.createArrayNode();
            source.forEach(value -> result.add(sanitize(value, "")));
            return result;
        }
        if (source.isTextual()) {
            return TextNode.valueOf(sanitizeText(fieldName, source.textValue()));
        }
        return source.deepCopy();
    }

    private ObjectNode sanitizeObject(ObjectNode source) {
        ObjectNode result = json.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            result.set(field.getKey(), sanitize(field.getValue(), field.getKey()));
        }
        annotateBoundedToolOutput(source, result);
        return result;
    }

    private String sanitizeText(String fieldName, String value) {
        if (STRUCTURAL_TEXT_FIELDS.contains(fieldName)) {
            return value;
        }
        if ("argumentsJson".equals(fieldName)) {
            return sanitizeArguments(value);
        }
        return bound(redactInline(value));
    }

    private String sanitizeArguments(String arguments) {
        try {
            JsonNode parsed = json.readTree(arguments);
            String governed = json.writeValueAsString(sanitize(parsed, ""));
            if (governed.length() <= MAX_TEXT_CHARACTERS) {
                return governed;
            }
            return "{\"agentkit.persistence\":\"omitted\","
                    + "\"reason\":\"arguments exceeded durable size limit\"}";
        } catch (JsonProcessingException failure) {
            return bound(redactInline(arguments));
        }
    }

    private void annotateBoundedToolOutput(ObjectNode source, ObjectNode governed) {
        String contentField = toolOutputField(source);
        if (contentField == null || !source.path(contentField).isTextual()) {
            return;
        }
        String original = source.path(contentField).textValue();
        if (original.length() <= MAX_TEXT_CHARACTERS) {
            return;
        }
        ObjectNode metadata = governed.withObject("metadata");
        metadata.put(PERSISTENCE_PREFIX + "disposition", "truncated");
        metadata.put(PERSISTENCE_PREFIX + "originalCharacters", original.length());
        metadata.put(PERSISTENCE_PREFIX + "retainedCharacters", MAX_TEXT_CHARACTERS);
        metadata.put(PERSISTENCE_PREFIX + "artifact", "omitted");
    }

    private String toolOutputField(ObjectNode source) {
        if ("toolResult".equals(source.path("type").asText())) {
            return "text";
        }
        if (source.has("status") && source.has("content") && source.has("metadata")) {
            return "content";
        }
        return null;
    }

    private static boolean isSensitive(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.endsWith("token");
    }

    private static String redactInline(String value) {
        return INLINE_SECRET.matcher(value).replaceAll("$1" + REDACTED);
    }

    private static String bound(String value) {
        if (value.length() <= MAX_TEXT_CHARACTERS) {
            return value;
        }
        int retained = MAX_TEXT_CHARACTERS - TRUNCATION_MARKER.length();
        return value.substring(0, retained) + TRUNCATION_MARKER;
    }
}
