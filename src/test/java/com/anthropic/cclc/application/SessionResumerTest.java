package com.anthropic.cclc.application;

import com.anthropic.cclc.application.SessionResumer.SessionNotFoundException;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.ChatMessage;
import com.anthropic.cclc.domain.message.ToolResultMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import com.anthropic.cclc.infrastructure.memory.FileChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionResumerTest {

    @Test
    void restoresMessageHistoryFromStore(@TempDir Path dir) {
        FileChatMemoryStore store = new FileChatMemoryStore(dir);
        SessionId id = SessionId.of("session-1");
        List<ChatMessage> original = List.of(
                UserMessage.of("hi"),
                AiMessage.text("hello"),
                UserMessage.of("more"));
        store.save(id, original);

        Conversation conv = new SessionResumer(store).resume(id);

        assertThat(conv.messages()).containsExactlyElementsOf(original);
        assertThat(conv.sessionId()).isEqualTo(id);
    }

    @Test
    void doesNotReExecuteToolsDuringResume(@TempDir Path dir) {
        FileChatMemoryStore store = new FileChatMemoryStore(dir);
        SessionId id = SessionId.of("session-1");
        ToolUseId useId = new ToolUseId("u1");
        List<ChatMessage> original = List.of(
                UserMessage.of("read it"),
                AiMessage.of("calling",
                        List.of(new ToolUseRequest(useId, "Read", "{\"path\":\"a.txt\"}"))),
                ToolResultMessage.of(useId, "file content"),
                AiMessage.text("done"));
        store.save(id, original);

        Conversation conv = new SessionResumer(store).resume(id);

        assertThat(conv.messages()).hasSize(4);
        assertThat(conv.messages()).containsExactlyElementsOf(original);
        // resume only loads messages — there's no Tool execution layer involved
    }

    @Test
    void failsGracefullyOnMissingSession(@TempDir Path dir) {
        FileChatMemoryStore store = new FileChatMemoryStore(dir);
        SessionResumer resumer = new SessionResumer(store);

        assertThatThrownBy(() -> resumer.resume(SessionId.of("missing")))
                .isInstanceOf(SessionNotFoundException.class);
    }
}
