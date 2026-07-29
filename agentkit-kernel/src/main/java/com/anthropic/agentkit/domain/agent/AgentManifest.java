package com.anthropic.agentkit.domain.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Self-description and typed entry point published by one agent package. */
public record AgentManifest<I, O>(
        AgentId id,
        String description,
        AgentEntryPoint<I, O> entryPoint,
        Set<ConfigKey> requiredConfigKeys,
        CapabilityDescriptor capabilities) {

    public AgentManifest {
        Objects.requireNonNull(id, "id");
        requireDescription(description);
        Objects.requireNonNull(entryPoint, "entryPoint");
        requiredConfigKeys = immutableConfig(requiredConfigKeys);
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(entryPoint.requestType(), "entryPoint.requestType");
        Objects.requireNonNull(entryPoint.resultType(), "entryPoint.resultType");
    }

    private static void requireDescription(String description) {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }

    private static Set<ConfigKey> immutableConfig(Set<ConfigKey> keys) {
        LinkedHashSet<ConfigKey> copy = new LinkedHashSet<>(
                Objects.requireNonNull(keys, "requiredConfigKeys"));
        if (copy.contains(null)) {
            throw new NullPointerException("requiredConfigKeys contains null");
        }
        return Collections.unmodifiableSet(copy);
    }
}
