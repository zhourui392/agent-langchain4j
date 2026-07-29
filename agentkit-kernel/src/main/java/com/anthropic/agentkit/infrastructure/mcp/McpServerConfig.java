package com.anthropic.agentkit.infrastructure.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable server declaration; authentication fields reference SecretProvider names. */
public record McpServerConfig(
        String id,
        McpTransportSpec transport,
        Duration initializationTimeout,
        Duration callTimeout,
        int eagerToolLimit) {

    private static final Duration DEFAULT_INITIALIZATION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final int DEFAULT_EAGER_TOOL_LIMIT = 32;

    public McpServerConfig {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("MCP server id must match [A-Za-z0-9_-]+: " + id);
        }
        Objects.requireNonNull(transport, "transport");
        requirePositive(initializationTimeout, "initializationTimeout");
        requirePositive(callTimeout, "callTimeout");
        if (eagerToolLimit < 1) {
            throw new IllegalArgumentException("eagerToolLimit must be positive");
        }
    }

    public static McpServerConfig stdio(String id, List<String> command) {
        return defaults(id, new McpTransportSpec.Stdio(command, Map.of(), Map.of()));
    }

    public static McpServerConfig http(String id, URI endpoint) {
        return defaults(id, new McpTransportSpec.Http(endpoint, Map.of(), Map.of()));
    }

    public McpServerConfig withEagerToolLimit(int limit) {
        return copy(transport, initializationTimeout, callTimeout, limit);
    }

    public McpServerConfig withInitializationTimeout(Duration timeout) {
        return copy(transport, timeout, callTimeout, eagerToolLimit);
    }

    public McpServerConfig withCallTimeout(Duration timeout) {
        return copy(transport, initializationTimeout, timeout, eagerToolLimit);
    }

    public McpServerConfig withEnvironment(String name, String value) {
        McpTransportSpec.Stdio stdio = requireStdio();
        Map<String, String> environment = appended(stdio.environment(), name, value);
        return copy(new McpTransportSpec.Stdio(
                stdio.command(), environment, stdio.secretEnvironment()),
                initializationTimeout, callTimeout, eagerToolLimit);
    }

    public McpServerConfig withSecretEnvironment(String name, String secretName) {
        McpTransportSpec.Stdio stdio = requireStdio();
        Map<String, String> secrets = appended(stdio.secretEnvironment(), name, secretName);
        return copy(new McpTransportSpec.Stdio(stdio.command(), stdio.environment(), secrets),
                initializationTimeout, callTimeout, eagerToolLimit);
    }

    public McpServerConfig withHeader(String name, String value) {
        McpTransportSpec.Http http = requireHttp();
        Map<String, String> headers = appended(http.headers(), name, value);
        return copy(new McpTransportSpec.Http(
                http.endpoint(), headers, http.secretHeaders()),
                initializationTimeout, callTimeout, eagerToolLimit);
    }

    public McpServerConfig withSecretHeader(String name, String secretName) {
        McpTransportSpec.Http http = requireHttp();
        Map<String, String> secrets = appended(http.secretHeaders(), name, secretName);
        return copy(new McpTransportSpec.Http(http.endpoint(), http.headers(), secrets),
                initializationTimeout, callTimeout, eagerToolLimit);
    }

    private static McpServerConfig defaults(String id, McpTransportSpec transport) {
        return new McpServerConfig(id, transport, DEFAULT_INITIALIZATION_TIMEOUT,
                DEFAULT_CALL_TIMEOUT, DEFAULT_EAGER_TOOL_LIMIT);
    }

    private McpServerConfig copy(
            McpTransportSpec nextTransport, Duration init, Duration call, int limit) {
        return new McpServerConfig(id, nextTransport, init, call, limit);
    }

    private McpTransportSpec.Stdio requireStdio() {
        if (transport instanceof McpTransportSpec.Stdio stdio) {
            return stdio;
        }
        throw new IllegalStateException("server " + id + " is not configured for stdio");
    }

    private McpTransportSpec.Http requireHttp() {
        if (transport instanceof McpTransportSpec.Http http) {
            return http;
        }
        throw new IllegalStateException("server " + id + " is not configured for HTTP");
    }

    private static Map<String, String> appended(
            Map<String, String> values, String name, String value) {
        if (name == null || name.isBlank() || value == null || value.isBlank()) {
            throw new IllegalArgumentException("binding name and value must not be blank");
        }
        Map<String, String> copy = new LinkedHashMap<>(values);
        copy.put(name, value);
        return Map.copyOf(copy);
    }

    private static void requirePositive(Duration value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }
}
