package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.anthropic.agentkit.domain.diagnosis.SecretDataPolicy;

import java.util.Objects;
import java.util.Set;

/**
 * Secret-free runtime capability advertised to a host readiness endpoint.
 *
 * @author alex
 */
public record DiagnosisCapability(String toolName, String dataSourceId, String environment,
                                  ReadinessStatus readiness, Set<String> operations,
                                  String reasonCode) {

    public DiagnosisCapability {
        toolName = requireText(toolName, "toolName");
        dataSourceId = clean(dataSourceId);
        environment = clean(environment);
        readiness = Objects.requireNonNull(readiness, "readiness");
        operations = Objects.requireNonNull(operations, "operations").stream()
                .map(operation -> requireText(operation, "operation"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        reasonCode = clean(reasonCode);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return SecretDataPolicy.sanitize(value);
    }

    private static String clean(String value) {
        return SecretDataPolicy.sanitize(value);
    }
}
