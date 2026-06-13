package com.anthropic.cclc.infrastructure.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicEndpointResolverTest {

    @Test
    void should_AppendV1_When_BaseUrlIsHostRoot() {
        String resolved = AnthropicEndpointResolver.resolveBaseUrl("https://www.packyapi.com");

        assertThat(resolved).isEqualTo("https://www.packyapi.com/v1");
    }

    @Test
    void should_KeepV1_When_BaseUrlAlreadyContainsV1() {
        String resolved = AnthropicEndpointResolver.resolveBaseUrl("https://www.packyapi.com/v1/");

        assertThat(resolved).isEqualTo("https://www.packyapi.com/v1");
    }

    @Test
    void should_RemoveMessages_When_BaseUrlIsFullMessagesEndpoint() {
        String resolved = AnthropicEndpointResolver.resolveBaseUrl("https://www.packyapi.com/v1/messages");

        assertThat(resolved).isEqualTo("https://www.packyapi.com/v1");
    }
}
