package com.anthropic.cclc.infrastructure.llm;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.ChatMessage;
import com.anthropic.cclc.domain.message.SystemMessage;
import com.anthropic.cclc.domain.message.ToolResultMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageMapperTest {

    @Test
    void mapsUserMessageRoundTrip() {
        UserMessage source = UserMessage.of("hi");
        ChatMessage back = MessageMapper.toDomain(MessageMapper.toLc(source));

        assertThat(back).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) back).text()).isEqualTo("hi");
    }

    @Test
    void mapsAiMessageWithToolRequestsRoundTrip() {
        ToolUseRequest req = new ToolUseRequest(
                new ToolUseId("u1"), "Bash", "{\"command\":\"ls\"}");
        AiMessage source = AiMessage.of("running ls", List.of(req));

        ChatMessage back = MessageMapper.toDomain(MessageMapper.toLc(source));

        assertThat(back).isInstanceOf(AiMessage.class);
        AiMessage ai = (AiMessage) back;
        assertThat(ai.text()).isEqualTo("running ls");
        assertThat(ai.toolUseRequests()).containsExactly(req);
    }

    @Test
    void mapsAiMessagePureTextRoundTrip() {
        AiMessage source = AiMessage.text("hello world");
        ChatMessage back = MessageMapper.toDomain(MessageMapper.toLc(source));

        assertThat(back).isInstanceOf(AiMessage.class);
        AiMessage ai = (AiMessage) back;
        assertThat(ai.text()).isEqualTo("hello world");
        assertThat(ai.toolUseRequests()).isEmpty();
    }

    @Test
    void mapsToolResultMessageRoundTrip() {
        ToolResultMessage source = ToolResultMessage.of(new ToolUseId("u1"), "ok");
        ChatMessage back = MessageMapper.toDomain(MessageMapper.toLc(source));

        assertThat(back).isInstanceOf(ToolResultMessage.class);
        ToolResultMessage tr = (ToolResultMessage) back;
        assertThat(tr.toolUseId().value()).isEqualTo("u1");
        assertThat(tr.text()).isEqualTo("ok");
    }

    @Test
    void mapsSystemMessageRoundTrip() {
        SystemMessage source = SystemMessage.of("you are helpful");
        ChatMessage back = MessageMapper.toDomain(MessageMapper.toLc(source));

        assertThat(back).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) back).text()).isEqualTo("you are helpful");
    }
}
