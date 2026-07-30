package com.anthropic.agentkit.domain.diagnosis;

/**
 * Logical environment identifier carried by a diagnosis scope.
 *
 * @author alex
 */
public record EnvironmentRef(String name) {

    private static final String UNKNOWN = "unknown";

    public EnvironmentRef {
        String safe = SecretDataPolicy.sanitize(name);
        name = safe.isBlank() ? UNKNOWN : safe;
    }

    public static EnvironmentRef named(String name) {
        return new EnvironmentRef(name);
    }

    public static EnvironmentRef unknown() {
        return new EnvironmentRef(UNKNOWN);
    }

    public boolean isKnown() {
        return !UNKNOWN.equals(name);
    }
}
