package com.anthropic.agentkit.infrastructure.tools.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @Test
    void should_RedactSecrets_When_SummarizingCommands() {
        String summary = LogSanitizer.summarizeCommand(
                "ANTHROPIC_API_KEY=sk-live --token abc123 Authorization: Bearer xyz password=secret");

        assertThat(summary)
                .doesNotContain("sk-live", "abc123", "xyz", "secret")
                .contains("API_KEY=***", "--token ***", "Authorization: ***", "password=***");
    }

    @Test
    void should_RedactLiteralValues_When_SummarizingSql() {
        String summary = LogSanitizer.summarizeSql(
                "SELECT * FROM users WHERE phone='13800000000' AND password='secret'");

        assertThat(summary)
                .doesNotContain("13800000000", "secret")
                .contains("phone='?'", "password=***");
    }

    @Test
    void should_RemoveQuery_When_StrippingUrl() {
        String summary = LogSanitizer.stripQuery("https://example.com/api?a=token&b=secret#frag");

        assertThat(summary).isEqualTo("https://example.com/api#frag");
    }
}
