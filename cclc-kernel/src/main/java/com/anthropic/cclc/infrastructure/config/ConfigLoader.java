package com.anthropic.cclc.infrastructure.config;

import com.anthropic.cclc.domain.permission.PermissionMode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Function;

public final class ConfigLoader {

    static final String ENV_PROVIDER = "CCLC_PROVIDER";
    static final String ENV_MODEL = "CCLC_MODEL";
    static final String ENV_MAX_TOKENS = "CCLC_MAX_TOKENS";
    static final String ENV_PERMISSION_MODE = "CCLC_PERMISSION_MODE";

    public static final String DEFAULT_OPENAI_MODEL = "gpt-5.5";
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-6";
    public static final int DEFAULT_MAX_TOKENS = 8192;
    static final PermissionMode DEFAULT_PERMISSION_MODE = PermissionMode.BYPASS;
    static final LlmProvider DEFAULT_PROVIDER = LlmProvider.OPENAI;

    private static final String[] ENV_API_KEYS = {
            "CCLC_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY"
    };
    private static final String[] ENV_BASE_URLS = {
            "CCLC_BASE_URL", "OPENAI_BASE_URL", "ANTHROPIC_BASE_URL"
    };

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Function<String, String> envSource;
    private final Path configFile;

    public ConfigLoader(Function<String, String> envSource, Path configFile) {
        this.envSource = envSource;
        this.configFile = configFile;
    }

    public static ConfigLoader fromSystem() {
        return new ConfigLoader(System::getenv, defaultConfigPath());
    }

    public AppConfig load() {
        Map<String, Object> fileValues = readFile();
        LlmProvider provider = resolveProvider(fileValues);
        String apiKey = resolveString(ENV_API_KEYS, "apiKey", fileValues, null);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("one of CCLC_API_KEY, OPENAI_API_KEY, "
                    + "ANTHROPIC_API_KEY is required (env or " + describeConfigFile() + ")");
        }
        String model = resolveString(ENV_MODEL, "model", fileValues, defaultModel(provider));
        int maxTokens = resolveInt(ENV_MAX_TOKENS, "maxTokens", fileValues, DEFAULT_MAX_TOKENS);
        String baseUrl = resolveString(ENV_BASE_URLS, "baseUrl", fileValues, null);
        PermissionMode permissionMode = resolvePermissionMode(fileValues);
        return new AppConfig(apiKey, model, maxTokens, baseUrl, permissionMode, provider);
    }

    private LlmProvider resolveProvider(Map<String, Object> fileValues) {
        String raw = resolveString(ENV_PROVIDER, "provider", fileValues, null);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PROVIDER;
        }
        return LlmProvider.parse(raw);
    }

    private static String defaultModel(LlmProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> DEFAULT_ANTHROPIC_MODEL;
            case OPENAI -> DEFAULT_OPENAI_MODEL;
        };
    }

    private PermissionMode resolvePermissionMode(Map<String, Object> fileValues) {
        String raw = resolveString(ENV_PERMISSION_MODE, "permissionMode", fileValues, null);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PERMISSION_MODE;
        }
        try {
            return PermissionMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "invalid permissionMode '" + raw + "', expected one of: DEFAULT, PLAN, BYPASS, AUTO");
        }
    }

    private Map<String, Object> readFile() {
        if (configFile == null || !Files.exists(configFile)) {
            return Map.of();
        }
        try {
            return JSON.readValue(Files.readString(configFile), MAP_TYPE);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read config file: " + configFile, ex);
        }
    }

    private String resolveString(String envKey, String fileKey,
                                  Map<String, Object> fileValues, String fallback) {
        return resolveString(new String[]{envKey}, fileKey, fileValues, fallback);
    }

    private String resolveString(String[] envKeys, String fileKey,
                                  Map<String, Object> fileValues, String fallback) {
        String fromEnv = firstEnvValue(envKeys);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        Object fromFile = fileValues.get(fileKey);
        if (fromFile != null) {
            return fromFile.toString();
        }
        return fallback;
    }

    private String firstEnvValue(String[] envKeys) {
        for (String envKey : envKeys) {
            String value = envSource.apply(envKey);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private int resolveInt(String envKey, String fileKey,
                            Map<String, Object> fileValues, int fallback) {
        String fromEnv = envSource.apply(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Integer.parseInt(fromEnv.trim());
        }
        Object fromFile = fileValues.get(fileKey);
        if (fromFile instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    private String describeConfigFile() {
        return configFile == null ? "config file" : configFile.toString();
    }

    private static Path defaultConfigPath() {
        return Paths.get(System.getProperty("user.home", "."), ".claude-code-j", "config.json");
    }
}
