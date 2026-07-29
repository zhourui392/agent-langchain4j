package com.anthropic.agentkit.infrastructure.mcp;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Transport configuration containing secret names, never resolved secret values. */
public sealed interface McpTransportSpec
        permits McpTransportSpec.Stdio, McpTransportSpec.Http {

    record Stdio(
            List<String> command,
            Map<String, String> environment,
            Map<String, String> secretEnvironment) implements McpTransportSpec {
        public Stdio {
            command = List.copyOf(Objects.requireNonNull(command, "command"));
            if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("stdio command must not be empty or blank");
            }
            environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
            secretEnvironment = Map.copyOf(
                    Objects.requireNonNull(secretEnvironment, "secretEnvironment"));
        }
    }

    record Http(
            URI endpoint,
            Map<String, String> headers,
            Map<String, String> secretHeaders) implements McpTransportSpec {
        public Http {
            Objects.requireNonNull(endpoint, "endpoint");
            String scheme = endpoint.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("MCP HTTP endpoint must use http or https");
            }
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            secretHeaders = Map.copyOf(Objects.requireNonNull(secretHeaders, "secretHeaders"));
        }
    }
}
