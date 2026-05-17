package com.anthropic.cclc.infrastructure.config;

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
        ConfigLoader loader = new ConfigLoader(envOf(Map.of("ANTHROPIC_API_KEY", "sk-env")), null);

        AppConfig config = loader.load();

        assertThat(config.apiKey()).isEqualTo("sk-env");
        assertThat(config.model()).isEqualTo(ConfigLoader.DEFAULT_MODEL);
        assertThat(config.maxTokens()).isEqualTo(ConfigLoader.DEFAULT_MAX_TOKENS);
    }

    @Test
    void loadsAllValuesFromFileOnly(@TempDir Path dir) throws IOException {
        Path configFile = writeJson(dir,
                "{\"apiKey\":\"sk-file\",\"model\":\"claude-haiku\",\"maxTokens\":1024}");

        ConfigLoader loader = new ConfigLoader(envOf(Map.of()), configFile);
        AppConfig config = loader.load();

        assertThat(config.apiKey()).isEqualTo("sk-file");
        assertThat(config.model()).isEqualTo("claude-haiku");
        assertThat(config.maxTokens()).isEqualTo(1024);
    }

    @Test
    void envOverridesFile(@TempDir Path dir) throws IOException {
        Path configFile = writeJson(dir,
                "{\"apiKey\":\"sk-file\",\"model\":\"claude-haiku\",\"maxTokens\":1024}");

        ConfigLoader loader = new ConfigLoader(
                envOf(Map.of("ANTHROPIC_API_KEY", "sk-env",
                        "CCLC_MODEL", "claude-opus",
                        "CCLC_MAX_TOKENS", "2048")),
                configFile);
        AppConfig config = loader.load();

        assertThat(config.apiKey()).isEqualTo("sk-env");
        assertThat(config.model()).isEqualTo("claude-opus");
        assertThat(config.maxTokens()).isEqualTo(2048);
    }

    @Test
    void missingApiKeyFailsFast() {
        ConfigLoader loader = new ConfigLoader(envOf(Map.of()), null);

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void treatsBlankEnvAsAbsent(@TempDir Path dir) throws IOException {
        Path configFile = writeJson(dir, "{\"apiKey\":\"sk-file\"}");
        ConfigLoader loader = new ConfigLoader(envOf(Map.of("ANTHROPIC_API_KEY", "   ")), configFile);

        assertThat(loader.load().apiKey()).isEqualTo("sk-file");
    }

    @Test
    void baseUrlAbsentByDefault() {
        ConfigLoader loader = new ConfigLoader(envOf(Map.of("ANTHROPIC_API_KEY", "sk-env")), null);

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
                envOf(Map.of("ANTHROPIC_BASE_URL", "https://from-env.example.com")),
                configFile);

        assertThat(loader.load().baseUrl()).isEqualTo("https://from-env.example.com");
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
