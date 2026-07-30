package com.anthropic.agentkit.domain.diagnosis;

/**
 * Non-secret host description of the environment bound to a diagnosis run.
 *
 * @author alex
 */
public record EnvironmentContext(String name, String cluster, String region) {

    private static final String UNKNOWN = "unknown";

    public EnvironmentContext {
        name = clean(name);
        cluster = clean(cluster);
        region = clean(region);
    }

    public static EnvironmentContext unknown() {
        return new EnvironmentContext(UNKNOWN, UNKNOWN, UNKNOWN);
    }

    public static EnvironmentContext named(String name) {
        return new EnvironmentContext(name, UNKNOWN, UNKNOWN);
    }

    public boolean isKnown() {
        return !UNKNOWN.equals(name);
    }

    private static String clean(String value) {
        String safe = SecretDataPolicy.sanitize(value);
        return safe.isBlank() ? UNKNOWN : safe;
    }
}
