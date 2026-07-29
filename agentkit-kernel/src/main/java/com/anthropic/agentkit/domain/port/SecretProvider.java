package com.anthropic.agentkit.domain.port;

import java.util.Objects;
import java.util.Optional;

/** Supplies named secrets through an explicit execution scope. */
@FunctionalInterface
public interface SecretProvider {

    Optional<String> find(SecretScope scope, String name);

    static SecretProvider none() {
        return (scope, name) -> {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(name, "name");
            return Optional.empty();
        };
    }
}
