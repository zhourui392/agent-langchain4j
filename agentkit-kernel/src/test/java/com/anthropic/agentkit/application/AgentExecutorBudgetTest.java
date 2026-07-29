package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static com.anthropic.agentkit.testsupport.TestRunContexts.runContext;

class AgentExecutorBudgetTest {

    @Test
    void rejectsNextLlmTurnWhenMaxTurnsReached() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(new ToolUseId("t-1"), "Read", "{}"))))
                .enqueue(AiMessage.text("done"));
        AgentExecutor executor = new AgentExecutor(llm, tools(), allowAll());
        Conversation conversation = conversation();

        AgentRunResult result = executor.run(conversation, runContext(
                conversation, new CancellationToken(), AgentBudget.of(1, 10, 10_000))).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(llm.capturedRequests()).hasSize(1);
    }

    @Test
    void budgetExhaustionSettlesEveryToolUseInOrder() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(
                        new ToolUseRequest(new ToolUseId("t-1"), "Read", "{}"),
                        new ToolUseRequest(new ToolUseId("t-2"), "Read", "{}"))));
        FakeTool read = FakeTool.readOnlyReturning("Read", "must not execute");
        AgentExecutor executor = new AgentExecutor(
                llm, new ToolRegistry().register(read), allowAll());
        Conversation conversation = conversation();

        AgentRunResult result = executor.run(conversation, runContext(
                conversation, new CancellationToken(), AgentBudget.of(2, 0, 10_000))).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(llm.capturedRequests()).hasSize(1);
        assertThat(read.callCount()).isZero();
        assertThat(conversation.messages()).filteredOn(ToolResultMessage.class::isInstance)
                .extracting(message -> ((ToolResultMessage) message).status())
                .containsExactly(ToolResultStatus.BUDGET_EXHAUSTED, ToolResultStatus.BUDGET_EXHAUSTED);
    }

    @Test
    void rejectsToolCallsAfterInputTokenBudgetIsExceeded() {
        LlmClient llm = (request, handler) -> {
            handler.onUsage(11, 0, 0);
            handler.onComplete(new AiMessage("", List.of(
                    new ToolUseRequest(new ToolUseId("t-1"), "Read", "{}"))));
        };
        AgentExecutor executor = new AgentExecutor(llm, tools(), allowAll());
        Conversation conversation = conversation();

        AgentRunResult result = executor.run(conversation, runContext(
                conversation, new CancellationToken(), AgentBudget.of(5, 10, 10))).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(conversation.messages()).filteredOn(ToolResultMessage.class::isInstance)
                .extracting(message -> ((ToolResultMessage) message).status())
                .containsExactly(ToolResultStatus.BUDGET_EXHAUSTED);
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
        return new PermissionService((invocation, tool, mode) -> com.anthropic.agentkit.domain.permission.Decision.ALLOW,
                (invocation, tool) -> {
                    throw new IllegalStateException("not used");
                },
                com.anthropic.agentkit.domain.permission.PermissionMode.BYPASS);
    }
}
