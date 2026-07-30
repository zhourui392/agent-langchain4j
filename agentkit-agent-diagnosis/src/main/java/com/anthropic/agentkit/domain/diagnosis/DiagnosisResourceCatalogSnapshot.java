package com.anthropic.agentkit.domain.diagnosis;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, run-scoped view of host-visible diagnosis resources.
 *
 * @author alex
 */
public record DiagnosisResourceCatalogSnapshot(long generation, List<ServiceRef> services,
                                               List<DataSourceBinding> bindings) {

    public DiagnosisResourceCatalogSnapshot {
        services = List.copyOf(Objects.requireNonNull(services, "services"));
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        requireUniqueServices(services);
        requireKnownServices(services, bindings);
        requireUniqueDataSources(bindings);
        requireUnambiguousAliases(bindings);
    }

    public static DiagnosisResourceCatalogSnapshot empty() {
        return new DiagnosisResourceCatalogSnapshot(0, List.of(), List.of());
    }

    public ServiceResolution resolveService(
            EnvironmentRef environment, ServiceSelection selection) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(selection, "selection");
        List<ServiceRef> visible = visibleServices(environment);
        if (visible.isEmpty()) {
            return ServiceResolution.notConfigured();
        }
        String preferred = selection.preferredName();
        if (!preferred.isBlank()) {
            return visible.stream().filter(service -> service.matches(preferred)).findFirst()
                    .map(service -> ServiceResolution.resolved(preferred, service))
                    .orElseGet(() -> ServiceResolution.unknown(preferred, visible));
        }
        return visible.size() == 1
                ? ServiceResolution.resolved("", visible.getFirst())
                : ServiceResolution.ambiguous(visible);
    }

    public List<DataSourceBinding> dataSourcesFor(
            EnvironmentRef environment, ServiceRef service) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(service, "service");
        return bindings.stream()
                .filter(binding -> binding.environment().equals(environment))
                .filter(binding -> binding.service().equals(service))
                .sorted(java.util.Comparator.comparing(DataSourceBinding::dataSourceId))
                .toList();
    }

    public List<ServiceRef> visibleServices(EnvironmentRef environment) {
        LinkedHashSet<ServiceRef> visible = new LinkedHashSet<>();
        bindings.stream()
                .filter(binding -> binding.environment().equals(environment))
                .map(DataSourceBinding::service)
                .sorted()
                .forEach(visible::add);
        return List.copyOf(visible);
    }

    private static void requireUniqueServices(List<ServiceRef> services) {
        long unique = services.stream().map(service -> normalize(service.name())).distinct().count();
        if (unique != services.size()) {
            throw new IllegalArgumentException("service names must be unique");
        }
    }

    private static void requireKnownServices(
            List<ServiceRef> services, List<DataSourceBinding> bindings) {
        for (DataSourceBinding binding : bindings) {
            if (!services.contains(binding.service())) {
                throw new IllegalArgumentException(
                        "binding references unknown service: " + binding.service().name());
            }
        }
    }

    private static void requireUniqueDataSources(List<DataSourceBinding> bindings) {
        long unique = bindings.stream()
                .map(binding -> binding.environment().name() + "\u0000" + binding.dataSourceId())
                .distinct().count();
        if (unique != bindings.size()) {
            throw new IllegalArgumentException("data source ids must be unique per environment");
        }
    }

    private static void requireUnambiguousAliases(List<DataSourceBinding> bindings) {
        Map<String, ServiceRef> owners = new HashMap<>();
        for (DataSourceBinding binding : bindings) {
            for (String name : binding.service().lookupNames()) {
                String key = normalize(binding.environment().name()) + "\u0000" + normalize(name);
                ServiceRef previous = owners.putIfAbsent(key, binding.service());
                if (previous != null && !previous.equals(binding.service())) {
                    throw new IllegalArgumentException("service alias is ambiguous: " + name);
                }
            }
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
