package com.anthropic.agentkit.infrastructure.diagnosis;

import java.util.Objects;
import java.util.Set;

/**
 * Host supplied production guardrails for diagnosis tool assembly.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record DiagnosisToolPolicy(Set<String> allowedHttpHosts, Set<String> allowedDubboMethods) {

    public DiagnosisToolPolicy {
        allowedHttpHosts = safeCopy(allowedHttpHosts);
        allowedDubboMethods = safeCopy(allowedDubboMethods);
    }

    public static DiagnosisToolPolicy allowAll() {
        return new DiagnosisToolPolicy(Set.of(), Set.of());
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
