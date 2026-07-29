package com.anthropic.agentkit.application.agent;

import com.anthropic.agentkit.domain.agent.AgentEntryPoint;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentManifest;
import com.anthropic.agentkit.domain.agent.ConfigKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Explicit host registry for typed agent manifests. */
public final class AgentRegistry {

    private final Map<AgentId, AgentManifest<?, ?>> manifests;
    private final Set<ConfigKey> configuredKeys;

    public AgentRegistry(List<AgentManifest<?, ?>> manifests, Set<ConfigKey> configuredKeys) {
        this.manifests = index(manifests);
        this.configuredKeys = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(configuredKeys, "configuredKeys")));
    }

    public List<AgentManifest<?, ?>> manifests() {
        return List.copyOf(manifests.values());
    }

    public <O> O dispatch(AgentId id, Object request, Class<O> resultType) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(resultType, "resultType");
        AgentManifest<?, ?> manifest = requireManifest(id);
        requireConfiguration(manifest);
        requireRequestType(manifest.entryPoint(), request);
        requireResultType(manifest.entryPoint(), resultType);
        return resultType.cast(invoke(manifest.entryPoint(), request));
    }

    private AgentManifest<?, ?> requireManifest(AgentId id) {
        AgentManifest<?, ?> manifest = manifests.get(Objects.requireNonNull(id, "id"));
        if (manifest == null) {
            throw new IllegalArgumentException("unknown agent: " + id);
        }
        return manifest;
    }

    private void requireConfiguration(AgentManifest<?, ?> manifest) {
        LinkedHashSet<ConfigKey> missing = new LinkedHashSet<>(manifest.requiredConfigKeys());
        missing.removeAll(configuredKeys);
        if (!missing.isEmpty()) {
            String names = missing.stream().map(ConfigKey::value).collect(Collectors.joining(", "));
            throw new AgentConfigurationException(
                    "agent " + manifest.id() + " missing required config: " + names);
        }
    }

    private static void requireRequestType(AgentEntryPoint<?, ?> entryPoint, Object request) {
        if (!entryPoint.requestType().isInstance(request)) {
            throw new IllegalArgumentException(
                    "agent request must be " + entryPoint.requestType().getName());
        }
    }

    private static void requireResultType(AgentEntryPoint<?, ?> entryPoint, Class<?> resultType) {
        if (!resultType.isAssignableFrom(entryPoint.resultType())) {
            throw new IllegalArgumentException(
                    "agent result must be " + entryPoint.resultType().getName());
        }
    }

    private static Object invoke(AgentEntryPoint<?, ?> entryPoint, Object request) {
        return invokeCaptured(entryPoint, request);
    }

    private static <I, O> O invokeCaptured(AgentEntryPoint<I, O> entryPoint, Object request) {
        return entryPoint.invoke(entryPoint.requestType().cast(request));
    }

    private static Map<AgentId, AgentManifest<?, ?>> index(
            List<AgentManifest<?, ?>> manifests) {
        LinkedHashMap<AgentId, AgentManifest<?, ?>> indexed = new LinkedHashMap<>();
        for (AgentManifest<?, ?> manifest : Objects.requireNonNull(manifests, "manifests")) {
            Objects.requireNonNull(manifest, "manifest");
            if (indexed.putIfAbsent(manifest.id(), manifest) != null) {
                throw new IllegalArgumentException("duplicate agent id: " + manifest.id());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }
}
