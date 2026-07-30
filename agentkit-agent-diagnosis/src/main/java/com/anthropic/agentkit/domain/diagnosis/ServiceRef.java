package com.anthropic.agentkit.domain.diagnosis;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical service identity and host-owned user-facing aliases.
 *
 * @author alex
 */
public record ServiceRef(String name, Set<String> aliases) implements Comparable<ServiceRef> {

    public ServiceRef {
        name = requireText(name, "name");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String alias : Objects.requireNonNull(aliases, "aliases")) {
            String value = requireText(alias, "alias");
            if (!normalize(value).equals(normalize(name))) {
                normalized.add(value);
            }
        }
        aliases = Set.copyOf(normalized);
    }

    public boolean matches(String candidate) {
        String normalized = normalize(candidate);
        return normalize(name).equals(normalized)
                || aliases.stream().map(ServiceRef::normalize).anyMatch(normalized::equals);
    }

    public Set<String> lookupNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(name);
        names.addAll(aliases);
        return Set.copyOf(names);
    }

    @Override
    public int compareTo(ServiceRef other) {
        return name.compareTo(other.name);
    }

    private static String requireText(String value, String field) {
        return SecretDataPolicy.required(value, field);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
