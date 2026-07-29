package com.anthropic.agentkit.domain.conversation;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationTest {

    private final SessionId session = SessionId.of("test-session");

    @Test
    void appendsMessagesInOrder() {
        Conversation conv = new Conversation(session);
        UserMessage u = UserMessage.of("hi");
        AiMessage a = AiMessage.text("hello");
        UserMessage u2 = UserMessage.of("more");

        conv.append(u);
        conv.append(a);
        conv.append(u2);

        assertThat(conv.messages()).containsExactly(u, a, u2);
    }

    @Test
    void rejectsToolResultWithoutMatchingToolUse() {
        Conversation conv = new Conversation(session);
        ToolResultMessage orphan = ToolResultMessage.of(new ToolUseId("never-used"), "result");

        assertThatThrownBy(() -> conv.append(orphan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never-used");
    }

    @Test
    void rejectsToolResultForAlreadySettledToolUse() {
        Conversation conv = new Conversation(session);
        ToolUseId id = new ToolUseId("u1");
        AiMessage withToolUse = AiMessage.of("calling",
                List.of(new ToolUseRequest(id, "Bash", "{}")));
        conv.append(withToolUse);
        conv.append(ToolResultMessage.of(id, "first result"));

        assertThatThrownBy(() -> conv.append(ToolResultMessage.of(id, "second")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("u1");
    }

    @Test
    void acceptsToolResultMatchingPendingToolUse() {
        Conversation conv = new Conversation(session);
        ToolUseId id = new ToolUseId("u1");
        conv.append(AiMessage.of("calling", List.of(new ToolUseRequest(id, "Bash", "{}"))));

        ToolResultMessage result = ToolResultMessage.of(id, "ok");
        conv.append(result);

        assertThat(conv.messages()).hasSize(2);
        assertThat(conv.messages().get(1)).isEqualTo(result);
    }

    @Test
    void rejectsDuplicateToolUseIdWithinAssistantTurn() {
        Conversation conv = new Conversation(session);
        ToolUseId duplicate = new ToolUseId("duplicate");
        AiMessage assistant = AiMessage.of("calling", List.of(
                new ToolUseRequest(duplicate, "Read", "{}"),
                new ToolUseRequest(duplicate, "Grep", "{}")));

        assertThatThrownBy(() -> conv.append(assistant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThat(conv.messages()).isEmpty();
    }

    @Test
    void rejectsNextAssistantMessageWhileToolBatchPending() {
        Conversation conv = conversationWithPendingToolUse();

        assertThatThrownBy(() -> conv.append(AiMessage.text("too early")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");
    }

    @Test
    void rejectsNextUserMessageWhileToolBatchPending() {
        Conversation conv = conversationWithPendingToolUse();

        assertThatThrownBy(() -> conv.append(UserMessage.of("too early")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");
    }

    @Test
    void rejectsToolResultsOutsideOriginalBatchOrder() {
        Conversation conv = new Conversation(session);
        ToolUseId first = new ToolUseId("first");
        ToolUseId second = new ToolUseId("second");
        conv.append(AiMessage.of("calling", List.of(
                new ToolUseRequest(first, "Read", "{}"),
                new ToolUseRequest(second, "Grep", "{}"))));

        assertThatThrownBy(() -> conv.append(ToolResultMessage.of(second, "out of order")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order");
    }

    @Test
    void exposesSessionId() {
        Conversation conv = new Conversation(session);
        assertThat(conv.sessionId()).isEqualTo(session);
    }

    @Test
    void messagesListIsUnmodifiable() {
        Conversation conv = new Conversation(session);
        conv.append(UserMessage.of("hi"));
        List<ChatMessage> snapshot = conv.messages();

        assertThatThrownBy(() -> snapshot.add(UserMessage.of("nope")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private Conversation conversationWithPendingToolUse() {
        Conversation conv = new Conversation(session);
        conv.append(AiMessage.of("calling", List.of(new ToolUseRequest(
                new ToolUseId("pending"), "Read", "{}"))));
        return conv;
    }
}
