package com.anthropic.agentkit.infrastructure.config;

import com.anthropic.agentkit.domain.port.SecretProvider;
import com.anthropic.agentkit.domain.port.SecretScope;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Infrastructure adapter for environment-backed scoped secret lookup. */
public final class EnvironmentSecretProvider implements SecretProvider {

    private final Function<String, String> environment;

    public EnvironmentSecretProvider(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public static EnvironmentSecretProvider system() {
        return new EnvironmentSecretProvider(System::getenv);
    }

    @Override
    public Optional<String> find(SecretScope scope, String name) {
        Objects.requireNonNull(scope, "scope");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("secret name must not be blank");
        }
        return Optional.ofNullable(environment.apply(name))
                .filter(value -> !value.isBlank());
    }
}
