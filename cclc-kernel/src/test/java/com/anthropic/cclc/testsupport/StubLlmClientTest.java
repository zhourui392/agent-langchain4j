package com.anthropic.cclc.testsupport;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.port.ChatRequest;
import com.anthropic.cclc.domain.port.LlmClient.StreamHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubLlmClientTest {

    private final ChatRequest anyRequest = ChatRequest.builder()
            .message(UserMessage.of("hi"))
            .build();

    @Test
    void replaysPreconfiguredResponsesInOrder() {
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.text("first"))
                .enqueue(AiMessage.text("second"));

        List<String> completions = new ArrayList<>();
        StreamHandler collect = new StreamHandler() {
            @Override public void onPartialText(String delta) {}
            @Override public void onComplete(AiMessage message) {
                completions.add(message.text());
            }
        };

        stub.streamChat(anyRequest, collect);
        stub.streamChat(anyRequest, collect);

        assertThat(completions).containsExactly("first", "second");
    }

    @Test
    void failsWhenExhausted() {
        StubLlmClient stub = new StubLlmClient();

        assertThatThrownBy(() -> stub.streamChat(anyRequest, noopHandler()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("exhausted");
    }

    @Test
    void capturesIncomingRequests() {
        StubLlmClient stub = new StubLlmClient().enqueue(AiMessage.text("ok"));
        stub.streamChat(anyRequest, noopHandler());

        assertThat(stub.capturedRequests()).hasSize(1);
        assertThat(stub.capturedRequests().get(0)).isSameAs(anyRequest);
    }

    @Test
    void emitsPartialTextBeforeComplete() {
        StubLlmClient stub = new StubLlmClient().enqueue(AiMessage.text("hello"));

        List<String> events = new ArrayList<>();
        StreamHandler trace = new StreamHandler() {
            @Override public void onPartialText(String delta) { events.add("partial:" + delta); }
            @Override public void onComplete(AiMessage message) { events.add("complete"); }
        };

        stub.streamChat(anyRequest, trace);
        assertThat(events).containsExactly("partial:hello", "complete");
    }

    private static StreamHandler noopHandler() {
        return new StreamHandler() {
            @Override public void onPartialText(String delta) {}
        };
    }
}
