package com.anthropic.agentkit.infrastructure.config;

import com.anthropic.agentkit.domain.port.SecretProvider;
import com.anthropic.agentkit.domain.port.SecretScope;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Infrastructure adapter for environment-backed scoped secret lookup. */
public final class EnvironmentSecretProvider implements SecretProvider {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSecretProvider.class);

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
        Optional<String> secret = Optional.ofNullable(environment.apply(name))
                .filter(value -> !value.isBlank());
        log.info("secret lookup: run={}, workspace={}, name={}, found={}",
                scope.runId(), scope.workspaceId(), name, secret.isPresent());
        return secret;
    }
}
