package com.anthropic.agentkit.infrastructure.config;

import com.anthropic.agentkit.domain.permission.PermissionMode;

import java.util.Objects;
import java.util.Optional;

public record AppConfig(String apiKey,
                         String model,
                         int maxTokens,
                         String baseUrl,
                         PermissionMode permissionMode,
                         LlmProvider provider) {

    public AppConfig {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(permissionMode, "permissionMode");
        Objects.requireNonNull(provider, "provider");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    public AppConfig(String apiKey, String model, int maxTokens) {
        this(apiKey, model, maxTokens, null, PermissionMode.BYPASS, LlmProvider.OPENAI);
    }

    public AppConfig(String apiKey, String model, int maxTokens, String baseUrl) {
        this(apiKey, model, maxTokens, baseUrl, PermissionMode.BYPASS, LlmProvider.OPENAI);
    }

    public AppConfig(String apiKey, String model, int maxTokens, String baseUrl,
                     PermissionMode permissionMode) {
        this(apiKey, model, maxTokens, baseUrl, permissionMode, LlmProvider.OPENAI);
    }

    public Optional<String> baseUrlIfPresent() {
        return baseUrl == null || baseUrl.isBlank() ? Optional.empty() : Optional.of(baseUrl);
    }
}
