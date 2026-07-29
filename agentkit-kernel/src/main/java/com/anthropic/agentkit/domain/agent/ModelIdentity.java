package com.anthropic.agentkit.domain.agent;

import java.util.Objects;

/** Provider-neutral identity of the model that handled a physical LLM attempt. */
public record ModelIdentity(String provider, String model) {

    private static final ModelIdentity UNKNOWN =
            new ModelIdentity("unknown", "unknown");

    public ModelIdentity {
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
    }

    public static ModelIdentity unknown() {
        return UNKNOWN;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
