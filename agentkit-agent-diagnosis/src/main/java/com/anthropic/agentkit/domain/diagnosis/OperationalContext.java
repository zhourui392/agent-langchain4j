package com.anthropic.agentkit.domain.diagnosis;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, non-secret operational facts supplied by the host for one run.
 *
 * @author alex
 */
public record OperationalContext(Instant now, ZoneId zoneId, EnvironmentContext environment,
                                 String defaultService, List<DataSourceView> dataSources,
                                 Map<String, String> attributes,
                                 ServiceResolution serviceResolution, long resourceGeneration) {

    private static final Instant UNKNOWN_NOW = Instant.EPOCH;
    public OperationalContext {
        now = Objects.requireNonNull(now, "now");
        zoneId = Objects.requireNonNull(zoneId, "zoneId");
        environment = Objects.requireNonNull(environment, "environment");
        defaultService = clean(defaultService);
        dataSources = List.copyOf(Objects.requireNonNull(dataSources, "dataSources"));
        attributes = safeAttributes(attributes);
        serviceResolution = serviceResolution == null
                ? ServiceResolution.notConfigured() : serviceResolution;
    }

    public OperationalContext(Instant now, ZoneId zoneId, EnvironmentContext environment,
                              String defaultService, List<DataSourceView> dataSources,
                              Map<String, String> attributes) {
        this(now, zoneId, environment, defaultService, dataSources, attributes,
                ServiceResolution.notConfigured(), 0);
    }

    public static OperationalContext unknown() {
        return legacy("");
    }

    public static OperationalContext legacy(String environment) {
        return new OperationalContext(
                UNKNOWN_NOW, ZoneId.of("UTC"), EnvironmentContext.named(environment),
                "", List.of(), Map.of());
    }

    public boolean hasKnownNow() {
        return !UNKNOWN_NOW.equals(now);
    }

    public boolean hasKnownEnvironment() {
        return environment.isKnown();
    }

    public OperationalContext withResources(DiagnosisResourceCatalogSnapshot resources) {
        Objects.requireNonNull(resources, "resources");
        EnvironmentRef environmentRef = hasKnownEnvironment()
                ? EnvironmentRef.named(environment.name()) : EnvironmentRef.unknown();
        ServiceResolution resolution = resources.resolveService(
                environmentRef, ServiceSelection.hostDefault(defaultService));
        String resolvedDefault = resolution.resolvedService()
                .map(ServiceRef::name).orElse(defaultService);
        List<DataSourceView> resolvedSources = resolution.resolvedService()
                .map(service -> resources.dataSourcesFor(environmentRef, service).stream()
                        .map(DataSourceBinding::toView).toList())
                .orElse(dataSources);
        return new OperationalContext(
                now, zoneId, environment, resolvedDefault, resolvedSources, attributes,
                resolution, resources.generation());
    }

    private static Map<String, String> safeAttributes(Map<String, String> values) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        Objects.requireNonNull(values, "attributes").forEach((key, value) -> {
            String cleanKey = requireAttributeKey(key);
            result.put(cleanKey, SecretDataPolicy.sanitize(Objects.toString(value, "")));
        });
        return Map.copyOf(result);
    }

    private static String requireAttributeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("attribute key must not be blank");
        }
        if (SecretDataPolicy.sensitiveKey(key)) {
            throw new IllegalArgumentException("sensitive attribute is not allowed: " + key);
        }
        return key.trim();
    }

    private static String clean(String value) {
        return SecretDataPolicy.sanitize(value);
    }
}
