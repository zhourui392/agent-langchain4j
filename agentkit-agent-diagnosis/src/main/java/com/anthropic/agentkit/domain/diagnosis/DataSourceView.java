package com.anthropic.agentkit.domain.diagnosis;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Secret-free capability view of one host-bound diagnosis data source.
 *
 * @author alex
 */
public record DataSourceView(String id, DataSourceType type, ReadinessStatus readiness,
                             Set<String> operations) {

    public DataSourceView {
        id = requireText(id, "id");
        type = Objects.requireNonNull(type, "type");
        readiness = Objects.requireNonNull(readiness, "readiness");
        operations = cleanOperations(operations);
    }

    private static Set<String> cleanOperations(Set<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : Objects.requireNonNull(values, "operations")) {
            result.add(requireText(value, "operation"));
        }
        return Set.copyOf(result);
    }

    private static String requireText(String value, String field) {
        return SecretDataPolicy.required(value, field);
    }
}
