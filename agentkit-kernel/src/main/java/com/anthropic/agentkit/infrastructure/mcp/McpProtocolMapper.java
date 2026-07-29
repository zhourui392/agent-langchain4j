package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates and maps raw MCP JSON-RPC payloads without owning transport lifecycle. */
final class McpProtocolMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> secretValues;

    McpProtocolMapper(List<String> secretValues) {
        this.secretValues = List.copyOf(secretValues);
    }

    ObjectNode arguments(ToolArguments arguments) {
        return JSON.valueToTree(arguments.values());
    }

    JsonNode result(JsonNode response, String operation) {
        JsonNode object = requireResponseObject(response, operation);
        if (object.has("error")) {
            throw new McpProtocolException("MCP " + operation + " returned an error");
        }
        JsonNode result = object.get("result");
        if (result == null || !result.isObject()) {
            throw new McpProtocolException("MCP " + operation + " response has no result");
        }
        return result;
    }

    McpCallResult callResult(JsonNode response) {
        JsonNode object = requireResponseObject(response, "tools/call");
        if (object.has("error")) {
            return McpCallResult.error(sanitize(errorMessage(object.path("error"))));
        }
        JsonNode result = result(object, "tools/call");
        boolean error = result.path("isError").asBoolean(false);
        JsonNode structured = result.get("structuredContent");
        return structured != null && !structured.isNull()
                ? structuredResult(error, structured) : contentResult(error, result);
    }

    List<McpToolDescriptor> descriptors(JsonNode tools) {
        if (!(tools instanceof ArrayNode array)) {
            throw new McpProtocolException("MCP tools/list result has no tools array");
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode tool : array) {
            descriptors.add(descriptor(tool));
        }
        return descriptors;
    }

    String nextCursor(JsonNode value) {
        return value == null || value.isNull() || !value.isTextual()
                || value.asText().isBlank() ? null : value.asText();
    }

    private McpCallResult structuredResult(boolean error, JsonNode structured) {
        return new McpCallResult(error, sanitize(structured.toString()),
                Map.of("mcp.content", "structured"));
    }

    private McpCallResult contentResult(boolean error, JsonNode result) {
        ArrayNode items = requireContent(result);
        return new McpCallResult(error, sanitize(contentText(items)),
                Map.of("mcp.content_count", String.valueOf(items.size())));
    }

    private static McpToolDescriptor descriptor(JsonNode tool) {
        String name = requireText(tool, "name");
        String description = tool.path("description").asText("");
        JsonNode schema = tool.get("inputSchema");
        if (schema == null || !schema.isObject()) {
            throw new McpProtocolException("invalid MCP input schema: " + name);
        }
        return new McpToolDescriptor(
                name, description, schema.toString(), annotations(tool.path("annotations")));
    }

    private static McpToolAnnotations annotations(JsonNode node) {
        boolean readOnly = node.path("readOnlyHint").asBoolean(false);
        boolean destructive = node.has("destructiveHint")
                ? node.path("destructiveHint").asBoolean(true) : !readOnly;
        boolean idempotent = node.path("idempotentHint").asBoolean(false);
        boolean openWorld = node.path("openWorldHint").asBoolean(true);
        return new McpToolAnnotations(readOnly, destructive, idempotent, openWorld);
    }

    private static JsonNode requireResponseObject(JsonNode response, String operation) {
        if (response == null || !response.isObject()) {
            throw new McpProtocolException("malformed MCP " + operation + " response");
        }
        return response;
    }

    private static ArrayNode requireContent(JsonNode result) {
        JsonNode content = result.get("content");
        if (!(content instanceof ArrayNode items)) {
            throw new McpProtocolException("MCP tools/call result has no content");
        }
        return items;
    }

    private static String contentText(ArrayNode items) {
        List<String> content = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject() || !item.hasNonNull("type")) {
                throw new McpProtocolException("malformed MCP content item");
            }
            content.add("text".equals(item.path("type").asText())
                    ? requireText(item, "text") : item.toString());
        }
        return String.join("\n", content);
    }

    private static String requireText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new McpProtocolException("missing MCP text field: " + field);
        }
        return value.asText();
    }

    private static String errorMessage(JsonNode error) {
        String message = error.path("message").asText("MCP server returned an error");
        return message.isBlank() ? "MCP server returned an error" : message;
    }

    private String sanitize(String value) {
        String sanitized = value;
        for (String secret : secretValues) {
            if (secret != null && !secret.isBlank()) {
                sanitized = sanitized.replace(secret, "***");
            }
        }
        return sanitized;
    }
}
