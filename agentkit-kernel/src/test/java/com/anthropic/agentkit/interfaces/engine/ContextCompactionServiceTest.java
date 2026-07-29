package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.application.context.ContextCompactionService;
import com.anthropic.agentkit.application.context.ContextDecision;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.conversation.TokenBudget;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextCompactionServiceTest {

    private final SessionId session = SessionId.of("s-1");

    @Test
    void noCompactBelowThreshold() {
        StubLlmClient llm = new StubLlmClient();
        ContextCompactionService service = new ContextCompactionService(llm, TokenBudget.of(100_000), 4);
        Conversation conversation = new Conversation(session);
        conversation.append(UserMessage.of("hi"));
        conversation.append(AiMessage.text("hello"));

        ContextDecision decision = service.beforeLlmCall(conversation, context());

        assertThat(decision.compacted()).isFalse();
        assertThat(llm.capturedRequests()).isEmpty();
    }

    @Test
    void compactsAboveThreshold() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("SUMMARY OF OLDER"));
        ContextCompactionService service = new ContextCompactionService(llm, TokenBudget.of(80), 2);
        Conversation conversation = filled(10);

        ContextDecision decision = service.beforeLlmCall(conversation, context());

        assertThat(decision.compacted()).isTrue();
        assertThat(conversation.messages()).hasSize(3);
        assertThat(conversation.messages().get(0).text()).contains("SUMMARY OF OLDER");
        assertThat(llm.capturedRequests()).hasSize(1);
    }

    @Test
    void preservesRecentMessagesAfterBoundary() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("SUM"));
        ContextCompactionService service = new ContextCompactionService(llm, TokenBudget.of(80), 2);
        Conversation conversation = filled(8);

        service.beforeLlmCall(conversation, context());
        List<ChatMessage> result = conversation.messages();

        assertThat(result.get(0).text()).contains("SUM");
        assertThat(result.get(result.size() - 1).text()).startsWith("msg-7-");
        assertThat(result.get(result.size() - 2).text()).startsWith("msg-6-");
    }

    private Conversation filled(int count) {
        Conversation conversation = new Conversation(session);
        for (int i = 0; i < count; i++) {
            conversation.append(UserMessage.of("msg-" + i + "-" + "x".repeat(40)));
        }
        return conversation;
    }

    private AgentRunContext context() {
        return AgentRunContext.create(
                session, Path.of("."), new CancellationToken(), AgentBudget.unlimited());
    }
}
