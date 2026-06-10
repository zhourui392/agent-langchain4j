package com.anthropic.cclc.application;

import com.anthropic.cclc.domain.agent.AgentBudget;
import com.anthropic.cclc.domain.agent.AgentBudgetExceededException;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import com.anthropic.cclc.testsupport.FakeTool;
import com.anthropic.cclc.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutorBudgetTest {

    @Test
    void rejectsNextLlmTurnWhenMaxTurnsReached() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(new ToolUseId("t-1"), "Read", "{}"))))
                .enqueue(AiMessage.text("done"));
        AgentExecutor executor = new AgentExecutor(llm, tools(), allowAll(),
                AgentBudget.of(1, 10, 10_000));

        assertThatThrownBy(() -> executor.run(conversation(), new CancellationToken()).join())
                .hasRootCauseInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("maxTurns");
        assertThat(llm.capturedRequests()).hasSize(1);
    }

    @Test
    void rejectsToolCallsWhenMaxToolCallsReached() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(new ToolUseId("t-1"), "Read", "{}"))));
        AgentExecutor executor = new AgentExecutor(llm, tools(), allowAll(),
                AgentBudget.of(5, 0, 10_000));

        assertThatThrownBy(() -> executor.run(conversation(), new CancellationToken()).join())
                .hasRootCauseInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("maxToolCalls");
    }

    @Test
    void rejectsToolCallsAfterInputTokenBudgetIsExceeded() {
        LlmClient llm = (request, handler) -> {
            handler.onUsage(11, 0, 0);
            handler.onComplete(new AiMessage("", List.of(
                    new ToolUseRequest(new ToolUseId("t-1"), "Read", "{}"))));
        };
        AgentExecutor executor = new AgentExecutor(llm, tools(), allowAll(),
                AgentBudget.of(5, 10, 10));

        assertThatThrownBy(() -> executor.run(conversation(), new CancellationToken()).join())
                .hasRootCauseInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("maxInputTokens");
    }

    private static Conversation conversation() {
        Conversation conversation = new Conversation(SessionId.of("s-budget"));
        conversation.append(UserMessage.of("diagnose"));
        return conversation;
    }

    private static ToolRegistry tools() {
        return new ToolRegistry().register(FakeTool.readOnlyReturning("Read", "ok"));
    }

    private static PermissionService allowAll() {
        return new PermissionService((invocation, tool, mode) -> com.anthropic.cclc.domain.permission.Decision.ALLOW,
                (invocation, tool) -> {
                    throw new IllegalStateException("not used");
                },
                com.anthropic.cclc.domain.permission.PermissionMode.BYPASS);
    }
}
