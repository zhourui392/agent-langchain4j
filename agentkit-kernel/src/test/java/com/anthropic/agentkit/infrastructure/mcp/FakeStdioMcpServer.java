package com.anthropic.agentkit.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/** JSON-RPC MCP subprocess used only by the stdio integration contract. */
public final class FakeStdioMcpServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeStdioMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        installCloseMarker();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(System.out))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode request = JSON.readTree(line);
                ObjectNode response = response(request);
                if (response != null) {
                    output.write(JSON.writeValueAsString(response));
                    output.newLine();
                    output.flush();
                }
            }
        } finally {
            writeCloseMarker();
        }
    }

    private static ObjectNode response(JsonNode request) {
        String method = request.path("method").asText();
        if (!request.hasNonNull("id")) {
            return null;
        }
        ObjectNode response = baseResponse(request.get("id"));
        switch (method) {
            case "initialize" -> response.set("result", initializeResult());
            case "tools/list" -> response.set("result", toolsResult());
            case "tools/call" -> response.set("result", callResult(request));
            default -> response.set("result", JSON.createObjectNode());
        }
        return response;
    }

    private static ObjectNode baseResponse(JsonNode id) {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        return response;
    }

    private static ObjectNode initializeResult() {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", "2025-06-18");
        result.putObject("capabilities").putObject("tools").put("listChanged", true);
        result.putObject("serverInfo").put("name", "fake-stdio").put("version", "1");
        return result;
    }

    private static ObjectNode toolsResult() {
        ObjectNode result = JSON.createObjectNode();
        ObjectNode tool = result.putArray("tools").addObject();
        tool.put("name", "echo");
        tool.put("description", "Echo one value");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties").putObject("value").put("type", "string");
        schema.putArray("required").add("value");
        tool.putObject("annotations")
                .put("readOnlyHint", true)
                .put("destructiveHint", false)
                .put("idempotentHint", true);
        return result;
    }

    private static ObjectNode callResult(JsonNode request) {
        String value = request.path("params").path("arguments").path("value").asText();
        ObjectNode result = JSON.createObjectNode();
        result.putArray("content").addObject()
                .put("type", "text").put("text", "echo:" + value);
        result.put("isError", false);
        return result;
    }

    private static void installCloseMarker() {
        Runtime.getRuntime().addShutdownHook(new Thread(FakeStdioMcpServer::writeCloseMarker));
    }

    private static void writeCloseMarker() {
        String marker = System.getenv("FAKE_MCP_CLOSED_FILE");
        if (marker == null || marker.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(marker), "closed");
        } catch (Exception ignored) {
            // Test process is already shutting down.
        }
    }
}
