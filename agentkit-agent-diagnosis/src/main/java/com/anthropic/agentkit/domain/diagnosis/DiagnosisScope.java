package com.anthropic.agentkit.domain.diagnosis;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable maximum query boundary approved for one diagnosis plan.
 *
 * @author alex
 */
public record DiagnosisScope(EnvironmentRef environment, Set<String> services,
                             TimeWindow timeWindow, Map<String, String> identifiers,
                             Map<String, String> tags) {

    public DiagnosisScope {
        environment = environment == null ? EnvironmentRef.unknown() : environment;
        services = cleanSet(services);
        timeWindow = timeWindow == null ? TimeWindow.unknown() : timeWindow;
        identifiers = safeMap(identifiers);
        tags = safeMap(tags);
    }

    public static DiagnosisScope unknown() {
        return new DiagnosisScope(
                EnvironmentRef.unknown(), Set.of(), TimeWindow.unknown(), Map.of(), Map.of());
    }

    public boolean isKnown() {
        return environment.isKnown() || !services.isEmpty() || timeWindow.isKnown()
                || !identifiers.isEmpty() || !tags.isEmpty();
    }

    public boolean contains(EnvironmentRef requestedEnvironment, Set<String> requestedServices,
                            TimeWindow requestedWindow) {
        return containsEnvironment(requestedEnvironment)
                && containsServices(requestedServices)
                && timeWindow.contains(requestedWindow);
    }

    public boolean permits(Map<String, Object> arguments) {
        if (!isKnown()) {
            return true;
        }
        Objects.requireNonNull(arguments, "arguments");
        EnvironmentRef requestedEnvironment = EnvironmentRef.named(text(arguments, "environment"));
        Set<String> requestedServices = service(arguments);
        TimeWindow requestedWindow = requestedWindow(arguments);
        return requestedWindow != null
                && contains(requestedEnvironment, requestedServices, requestedWindow)
                && containsFixedValues(identifiers, arguments)
                && containsFixedValues(tags, arguments);
    }

    private boolean containsEnvironment(EnvironmentRef requested) {
        return !environment.isKnown() || requested == null || !requested.isKnown()
                || environment.equals(requested);
    }

    private boolean containsServices(Set<String> requested) {
        return services.isEmpty() || requested == null || requested.isEmpty()
                || services.containsAll(cleanSet(requested));
    }

    private static Set<String> service(Map<String, Object> arguments) {
        String service = text(arguments, "service");
        return service.isBlank() ? Set.of() : Set.of(service);
    }

    private static TimeWindow requestedWindow(Map<String, Object> arguments) {
        String start = text(arguments, "startTime");
        String end = text(arguments, "endTime");
        if (start.isBlank() && end.isBlank()) {
            return TimeWindow.unknown();
        }
        if (start.isBlank() || end.isBlank()) {
            return null;
        }
        try {
            return new TimeWindow(Instant.parse(start), Instant.parse(end));
        } catch (RuntimeException invalidWindow) {
            return null;
        }
    }

    private static boolean containsFixedValues(Map<String, String> fixed,
                                               Map<String, Object> arguments) {
        return fixed.entrySet().stream().allMatch(entry -> {
            String actual = text(arguments, entry.getKey());
            return actual.isBlank() || entry.getValue().equals(actual);
        });
    }

    private static String text(Map<String, Object> arguments, String key) {
        return Objects.toString(arguments.get(key), "").trim();
    }

    private static Set<String> cleanSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).map(SecretDataPolicy::sanitize)
                .filter(value -> !value.isEmpty()).forEach(result::add);
        return Set.copyOf(result);
    }

    private static Map<String, String> safeMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && !key.isBlank() && !SecretDataPolicy.sensitiveKey(key)) {
                result.put(key.trim(), SecretDataPolicy.sanitize(Objects.toString(value, "")));
            }
        });
        return Map.copyOf(result);
    }
}
