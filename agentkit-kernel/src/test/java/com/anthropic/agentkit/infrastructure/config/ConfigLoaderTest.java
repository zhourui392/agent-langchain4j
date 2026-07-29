package com.anthropic.agentkit.infrastructure.config;

import com.anthropic.agentkit.domain.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    @Test
    void loadsApiKeyFromEnvironmentOnly() {
        ConfigLoader loader = new ConfigLoader(envOf(Map.of("AK_API_KEY", "sk-env")), null);

        AppConfig config = loader.load();

        assertThat(config.apiKey()).isEqualTo("sk-env");
        assertThat(config.provider()).isEqualTo(LlmProvider.OPENAI);
        assertThat(config.model()).isEqualTo("gpt-5.5");
        assertThat(config.maxTokens()).isEqualTo(ConfigLoader.DEFAULT_MAX_TOKENS);
    }

    @Test
    void loadsAllValuesFromFileOnly(@TempDir Path dir) throws IOException {
        Path configFile = writeJson(dir,
                "{\"apiKey\":\"sk-file\",\"provider\":\"anthropic\","
                        + "\"model\":\"claude-haiku\",\"maxTokens\":1024}");

        ConfigLoader loader = new ConfigLoader(envOf(Map.of()), configFile);
        AppConfig config = loader.load();

        assertThat(config.apiKey()).isEqualTo("sk-file");
        assertThat(config.provider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(config.model()).isEqualTo("claude-haiku");
        assertThat(config.maxTokens()).isEqualTo(1024);
    }

    @Test
    void envOverridesFile(@TempDir Path dir) throws IOException {
        Path configFile = writeJson(dir,
                "{\"apiKey\":\"sk-file\",\"model\":\"claude-haiku\",\"maxTokens\":1024}");

        ConfigLoader loader = new ConfigLoader(
                envOf(Map.of("AK_API_KEY", "sk-env",
                        "AK_PROVIDER", "openai",
                        "AK_MODEL", "claude-opus",
                        "AK_MAX_TOKENS", "2048")),
                configFile);
        AppConfig config = loader.load();

        assertThat(config.apiKey()).isEqualTo("sk-env");
        assertThat(config.provider()).isEqualTo(LlmProvider.OPENAI);
        assertThat(config.model()).isEqualTo("claude-opus");
        assertThat(config.maxTokens()).isEqualTo(2048);
    }

    @Test
    void missingApiKeyFailsFast() {
        ConfigLoader loader = new ConfigLoader(envOf(Map.of()), null);

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AK_API_KEY")
                .hasMessageContaining("OPENAI_API_KEY")
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void treatsBlankEnvAsAbsent(@TempDir Path dir) throws IOException {
        Path configFile = writeJson(dir, "{\"apiKey\":\"sk-file\"}");
        ConfigLoader loader = new ConfigLoader(envOf(Map.of("AK_API_KEY", "   ")), configFile);

        assertThat(loader.load().apiKey()).isEqualTo("sk-file");
    }

    @Test
    void baseUrlAbsentByDefault() {
        ConfigLoader loader = new ConfigLoader(envOf(Map.of("AK_API_KEY", "sk-env")), null);

        assertThat(loader.load().baseUrlIfPresent()).isEmpty();
    }

    @Test
    void loadsBaseUrlFromFile(@TempDir Path dir) throws IOException {
        Path configFile = writeJson(dir,
                "{\"apiKey\":\"sk-file\",\"baseUrl\":\"https://relay.example.com\"}");
        ConfigLoader loader = new ConfigLoader(envOf(Map.of()), configFile);

        assertThat(loader.load().baseUrl()).isEqualTo("https://relay.example.com");
    }

    @Test
    void envBaseUrlOverridesFile(@TempDir Path dir) throws IOException {
        Path configFile = writeJson(dir,
                "{\"apiKey\":\"sk-file\",\"baseUrl\":\"https://from-file.example.com\"}");
        ConfigLoader loader = new ConfigLoader(
                envOf(Map.of("AK_BASE_URL", "https://from-env.example.com")),
                configFile);

        assertThat(loader.load().baseUrl()).isEqualTo("https://from-env.example.com");
    }

    @Test
    void defaultConfigurationDoesNotBypassPermissions() {
        ConfigLoader loader = new ConfigLoader(envOf(Map.of("AK_API_KEY", "sk-env")), null);

        assertThat(loader.load().permissionMode()).isEqualTo(PermissionMode.DEFAULT);
    }

    @Test
    void readsPermissionModeFromEnv() {
        ConfigLoader loader = new ConfigLoader(
                envOf(Map.of("AK_API_KEY", "sk-env",
                        "AK_PERMISSION_MODE", "plan")),
                null);

        assertThat(loader.load().permissionMode()).isEqualTo(PermissionMode.PLAN);
    }

    @Test
    void readsPermissionModeFromFile(@TempDir Path dir) throws IOException {
        Path configFile = writeJson(dir,
                "{\"apiKey\":\"sk-file\",\"permissionMode\":\"DEFAULT\"}");
        ConfigLoader loader = new ConfigLoader(envOf(Map.of()), configFile);

        assertThat(loader.load().permissionMode()).isEqualTo(PermissionMode.DEFAULT);
    }

    @Test
    void invalidPermissionModeFailsFast() {
        ConfigLoader loader = new ConfigLoader(
                envOf(Map.of("AK_API_KEY", "sk-env",
                        "AK_PERMISSION_MODE", "WIDE_OPEN")),
                null);

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("permissionMode");
    }

    @Test
    void providerEnvOverridesFile(@TempDir Path dir) throws IOException {
        Path configFile = writeJson(dir,
                "{\"apiKey\":\"sk-file\",\"provider\":\"anthropic\"}");
        ConfigLoader loader = new ConfigLoader(
                envOf(Map.of("AK_PROVIDER", "openai")),
                configFile);

        assertThat(loader.load().provider()).isEqualTo(LlmProvider.OPENAI);
    }

    @Test
    void anthropicProviderUsesAnthropicDefaultModel() {
        ConfigLoader loader = new ConfigLoader(
                envOf(Map.of("ANTHROPIC_API_KEY", "sk-env", "AK_PROVIDER", "anthropic")),
                null);

        AppConfig config = loader.load();

        assertThat(config.provider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(config.model()).isEqualTo(ConfigLoader.DEFAULT_ANTHROPIC_MODEL);
    }

    @Test
    void apiKeyAliasesPreferGenericThenOpenAiThenAnthropic() {
        ConfigLoader loader = new ConfigLoader(
                envOf(Map.of(
                        "AK_API_KEY", "sk-generic",
                        "OPENAI_API_KEY", "sk-openai",
                        "ANTHROPIC_API_KEY", "sk-anthropic")),
                null);
        ConfigLoader openAiOnly = new ConfigLoader(
                envOf(Map.of(
                        "OPENAI_API_KEY", "sk-openai",
                        "ANTHROPIC_API_KEY", "sk-anthropic")),
                null);
        ConfigLoader anthropicOnly = new ConfigLoader(
                envOf(Map.of("ANTHROPIC_API_KEY", "sk-anthropic")),
                null);

        assertThat(loader.load().apiKey()).isEqualTo("sk-generic");
        assertThat(openAiOnly.load().apiKey()).isEqualTo("sk-openai");
        assertThat(anthropicOnly.load().apiKey()).isEqualTo("sk-anthropic");
    }

    @Test
    void baseUrlAliasesPreferGenericThenOpenAiThenAnthropic() {
        ConfigLoader loader = new ConfigLoader(
                envOf(Map.of(
                        "AK_API_KEY", "sk-env",
                        "AK_BASE_URL", "https://generic.example.com",
                        "OPENAI_BASE_URL", "https://openai.example.com",
                        "ANTHROPIC_BASE_URL", "https://anthropic.example.com")),
                null);

        assertThat(loader.load().baseUrl()).isEqualTo("https://generic.example.com");
    }

    @Test
    void invalidProviderFailsFast() {
        ConfigLoader loader = new ConfigLoader(
                envOf(Map.of("AK_API_KEY", "sk-env", "AK_PROVIDER", "ollama")),
                null);

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider")
                .hasMessageContaining("ANTHROPIC")
                .hasMessageContaining("OPENAI");
    }

    private static Function<String, String> envOf(Map<String, String> entries) {
        return entries::get;
    }

    private static Path writeJson(Path dir, String content) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, content);
        return file;
    }
}
