package com.anthropic.cclc.infrastructure.config;

import java.util.Objects;
import java.util.Optional;

public record AppConfig(String apiKey, String model, int maxTokens, String baseUrl) {

    public AppConfig {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(model, "model");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    public AppConfig(String apiKey, String model, int maxTokens) {
        this(apiKey, model, maxTokens, null);
    }

    public Optional<String> baseUrlIfPresent() {
        return baseUrl == null || baseUrl.isBlank() ? Optional.empty() : Optional.of(baseUrl);
    }
}
