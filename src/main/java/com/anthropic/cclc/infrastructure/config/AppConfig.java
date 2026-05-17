package com.anthropic.cclc.infrastructure.config;

import com.anthropic.cclc.domain.permission.PermissionMode;

import java.util.Objects;
import java.util.Optional;

public record AppConfig(String apiKey,
                         String model,
                         int maxTokens,
                         String baseUrl,
                         PermissionMode permissionMode) {

    public AppConfig {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(permissionMode, "permissionMode");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    public AppConfig(String apiKey, String model, int maxTokens) {
        this(apiKey, model, maxTokens, null, PermissionMode.BYPASS);
    }

    public AppConfig(String apiKey, String model, int maxTokens, String baseUrl) {
        this(apiKey, model, maxTokens, baseUrl, PermissionMode.BYPASS);
    }

    public Optional<String> baseUrlIfPresent() {
        return baseUrl == null || baseUrl.isBlank() ? Optional.empty() : Optional.of(baseUrl);
    }
}
