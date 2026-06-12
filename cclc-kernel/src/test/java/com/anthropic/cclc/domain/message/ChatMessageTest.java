package com.anthropic.cclc.domain.message;

import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMessageTest {

    @Test
    void userMessageRoleIsUserAndCarriesText() {
        UserMessage msg = UserMessage.of("hi");
        assertThat(msg.role()).isEqualTo(Role.USER);
        assertThat(msg.text()).isEqualTo("hi");
    }

    @Test
    void aiMessageCarriesToolUseRequests() {
        ToolUseRequest req = new ToolUseRequest(
                new ToolUseId("u1"), "Bash", "{\"command\":\"ls\"}");
        AiMessage ai = AiMessage.of("running", List.of(req));
        assertThat(ai.role()).isEqualTo(Role.AI);
        assertThat(ai.toolUseRequests()).containsExactly(req);
        assertThat(ai.hasToolUseRequests()).isTrue();
    }

    @Test
    void aiMessageWithoutToolRequestsIsPureText() {
        AiMessage ai = AiMessage.text("just text");
        assertThat(ai.toolUseRequests()).isEmpty();
        assertThat(ai.hasToolUseRequests()).isFalse();
    }

    @Test
    void systemMessageRoleIsSystem() {
        SystemMessage msg = SystemMessage.of("you are helpful");
        assertThat(msg.role()).isEqualTo(Role.SYSTEM);
        assertThat(msg.text()).isEqualTo("you are helpful");
    }

    @Test
    void toolResultMessageMustAssociateToolUseId() {
        ToolUseId id = new ToolUseId("u1");
        ToolResultMessage result = ToolResultMessage.of(id, "ok");
        assertThat(result.role()).isEqualTo(Role.TOOL);
        assertThat(result.toolUseId()).isEqualTo(id);
        assertThat(result.text()).isEqualTo("ok");
    }

    @Test
    void toolResultMessageRejectsNullToolUseId() {
        assertThatThrownBy(() -> ToolResultMessage.of(null, "ok"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void allMessagesShareSealedChatMessageInterface() {
        ChatMessage user = UserMessage.of("u");
        ChatMessage ai = AiMessage.text("a");
        ChatMessage system = SystemMessage.of("s");
        ChatMessage toolResult = ToolResultMessage.of(new ToolUseId("u1"), "r");
        assertThat(List.of(user, ai, system, toolResult))
                .extracting(ChatMessage::role)
                .containsExactly(Role.USER, Role.AI, Role.SYSTEM, Role.TOOL);
    }
}
