package com.anthropic.agentkit.infrastructure.diagnosis;

import java.util.Objects;
import java.util.Set;

/**
 * Host supplied production guardrails for diagnosis tool assembly.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record DiagnosisToolPolicy(Set<String> allowedHttpHosts,
                                  Set<String> allowedDubboAddresses,
                                  Set<String> allowedDubboMethods,
                                  Set<String> allowedEsIndices,
                                  Set<String> allowedMysqlSchemas,
                                  Set<String> allowedRedisKeyPrefixes) {

    public DiagnosisToolPolicy {
        allowedHttpHosts = safeCopy(allowedHttpHosts);
        allowedDubboAddresses = safeCopy(allowedDubboAddresses);
        allowedDubboMethods = safeCopy(allowedDubboMethods);
        allowedEsIndices = safeCopy(allowedEsIndices);
        allowedMysqlSchemas = safeCopy(allowedMysqlSchemas);
        allowedRedisKeyPrefixes = safeCopy(allowedRedisKeyPrefixes);
    }

    public DiagnosisToolPolicy(Set<String> allowedHttpHosts,
                               Set<String> allowedDubboMethods) {
        this(allowedHttpHosts, Set.of(), allowedDubboMethods,
                Set.of(), Set.of(), Set.of());
    }

    public static DiagnosisToolPolicy denyByDefault() {
        return new DiagnosisToolPolicy(
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    /**
     * @deprecated Empty production scopes are fail-closed; use {@link #denyByDefault()}.
     */
    @Deprecated(forRemoval = false)
    public static DiagnosisToolPolicy allowAll() {
        return denyByDefault();
    }

    private static Set<String> safeCopy(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList());
    }
}
