package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the SDK transports while AgentKit owns raw protocol/governance mapping. */
final class LangChain4jMcpSessionFactory implements McpSessionFactory {

    @Override
    public McpSession open(McpServerConfig config, ExecutionContext context) {
        ResolvedTransport resolved = switch (config.transport()) {
            case McpTransportSpec.Stdio stdio -> stdio(stdio, context);
            case McpTransportSpec.Http http -> http(http, config, context);
        };
        return ProtocolMcpSession.open(
                config, resolved.transport(), context, resolved.secretValues());
    }

    private static ResolvedTransport stdio(
            McpTransportSpec.Stdio spec, ExecutionContext context) {
        ResolvedBindings bindings = resolve(
                spec.environment(), spec.secretEnvironment(), context);
        McpTransport transport = new StdioMcpTransport.Builder()
                .command(spec.command())
                .environment(bindings.values())
                .logEvents(false)
                .build();
        return new ResolvedTransport(transport, bindings.secrets());
    }

    private static ResolvedTransport http(
            McpTransportSpec.Http spec, McpServerConfig config,
            ExecutionContext context) {
        ResolvedBindings bindings = resolve(spec.headers(), spec.secretHeaders(), context);
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url(spec.endpoint().toString())
                .customHeaders(bindings.values())
                .timeout(maximum(config.initializationTimeout(), config.callTimeout()))
                .logRequests(false)
                .logResponses(false)
                .build();
        return new ResolvedTransport(transport, bindings.secrets());
    }

    private static ResolvedBindings resolve(
            Map<String, String> literals, Map<String, String> secretBindings,
            ExecutionContext context) {
        Map<String, String> values = new LinkedHashMap<>(literals);
        List<String> secrets = new ArrayList<>();
        secretBindings.forEach((destination, secretName) -> {
            String secret = context.secret(secretName).orElseThrow(() ->
                    new IllegalArgumentException("missing required MCP secret: " + secretName));
            values.put(destination, secret);
            secrets.add(secret);
        });
        return new ResolvedBindings(Map.copyOf(values), List.copyOf(secrets));
    }

    private static Duration maximum(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private record ResolvedBindings(Map<String, String> values, List<String> secrets) { }

    private record ResolvedTransport(McpTransport transport, List<String> secretValues) { }
}
