package com.anthropic.agentkit.domain.diagnosis;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Secret-free logical binding from an environment/service to a diagnosis tool.
 *
 * @author alex
 */
public record DataSourceBinding(EnvironmentRef environment, ServiceRef service,
                                String dataSourceId, DataSourceType type, String toolName,
                                ReadinessStatus readiness, boolean defaultBinding,
                                Set<String> operations, Map<String, String> tags) {

    private static final Set<String> FORBIDDEN_TAG_TOKENS = Set.of(
            "key", "apikey", "token", "password", "secret", "authorization",
            "cookie", "credential", "endpoint", "url", "uri", "host", "header");

    public DataSourceBinding {
        environment = Objects.requireNonNull(environment, "environment");
        if (!environment.isKnown()) {
            throw new IllegalArgumentException("binding environment must be known");
        }
        service = Objects.requireNonNull(service, "service");
        dataSourceId = requireText(dataSourceId, "dataSourceId");
        type = Objects.requireNonNull(type, "type");
        toolName = requireText(toolName, "toolName");
        readiness = Objects.requireNonNull(readiness, "readiness");
        operations = SecretDataPolicy.sanitizeSet(
                Objects.requireNonNull(operations, "operations"), "operation");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("operations must not be empty");
        }
        tags = safeTags(tags);
    }

    public DataSourceView toView() {
        return new DataSourceView(dataSourceId, type, readiness, operations);
    }

    private static Map<String, String> safeTags(Map<String, String> values) {
        LinkedHashMap<String, String> safe = new LinkedHashMap<>();
        Objects.requireNonNull(values, "tags").forEach((key, value) -> {
            String cleanKey = requireText(key, "tag key");
            if (tokens(cleanKey).stream().anyMatch(FORBIDDEN_TAG_TOKENS::contains)) {
                throw new IllegalArgumentException("sensitive or connection tag is not allowed: " + key);
            }
            safe.put(cleanKey, SecretDataPolicy.sanitize(value));
        });
        return Map.copyOf(safe);
    }

    private static Set<String> tokens(String key) {
        String words = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return Arrays.stream(words.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String requireText(String value, String field) {
        return SecretDataPolicy.required(value, field);
    }
}
