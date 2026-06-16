package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConversationRebuilderTest {

    private final ConversationRebuilder rebuilder = new ConversationRebuilder();
    private final SessionId session = SessionId.of("s-1");

    @Test
    void rebuildsUserAssistantHistoryInOrder() {
        List<TurnMessage> history = List.of(
                new UserTurn("hello"),
                AssistantTurn.text("hi there"));

        Conversation conv = rebuilder.from(session, history, "next question");

        List<ChatMessage> msgs = conv.messages();
        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) msgs.get(0)).text()).isEqualTo("hello");
        assertThat(msgs.get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) msgs.get(1)).text()).isEqualTo("hi there");
        assertThat(msgs.get(2)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) msgs.get(2)).text()).isEqualTo("next question");
    }

    @Test
    void rebuildsToolUseAndToolResultPairing() {
        List<TurnMessage> history = List.of(
                new UserTurn("check logs"),
                new AssistantTurn("looking", List.of(new ToolCall("tu-1", "LogQuery", "{\"q\":\"x\"}"))),
                new ToolResultTurn("tu-1", "found 3 errors"));

        Conversation conv = rebuilder.from(session, history, "why?");

        List<ChatMessage> msgs = conv.messages();
        assertThat(msgs).hasSize(4);
        AiMessage ai = (AiMessage) msgs.get(1);
        assertThat(ai.toolUseRequests()).hasSize(1);
        assertThat(ai.toolUseRequests().get(0).id().value()).isEqualTo("tu-1");
        assertThat(ai.toolUseRequests().get(0).toolName()).isEqualTo("LogQuery");
        assertThat(ai.toolUseRequests().get(0).argumentsJson()).isEqualTo("{\"q\":\"x\"}");
        ToolResultMessage tr = (ToolResultMessage) msgs.get(2);
        assertThat(tr.toolUseId().value()).isEqualTo("tu-1");
        assertThat(tr.text()).isEqualTo("found 3 errors");
    }

    @Test
    void appendsCurrentUserMessageLastWithEmptyHistory() {
        Conversation conv = rebuilder.from(session, List.of(), "only message");

        assertThat(conv.messages()).hasSize(1);
        assertThat(((UserMessage) conv.messages().get(0)).text()).isEqualTo("only message");
    }

    @Test
    void rebuiltPairingSatisfiesInvariantChecker() {
        List<TurnMessage> history = List.of(
                new AssistantTurn("", List.of(new ToolCall("tu-9", "Grep", "{}"))),
                new ToolResultTurn("tu-9", "match"));

        // Conversation.append runs ToolUseInvariantChecker; mis-pairing throws.
        assertThatCode(() -> rebuilder.from(session, history, "ok")).doesNotThrowAnyException();
    }
}
