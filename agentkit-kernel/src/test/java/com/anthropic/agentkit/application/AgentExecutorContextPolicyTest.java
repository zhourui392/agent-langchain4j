package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.context.ContextCompactionService;
import com.anthropic.agentkit.application.context.ContextPolicy;
import com.anthropic.agentkit.application.tool.ToolOutputPolicy;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.TokenBudget;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.ContextWindowExceededException;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.anthropic.agentkit.testsupport.TestRunContexts.runContext;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutorContextPolicyTest {

    @Test
    void compactsBeforeEveryLlmCallWhenThresholdReached() {
        PolicyAwareLlm llm = new PolicyAwareLlm(false);
        Conversation conversation = longConversation("each-call");
        ContextPolicy policy = new ContextCompactionService(llm, TokenBudget.of(80), 1);
        ToolRegistry tools = new ToolRegistry().register(
                FakeTool.readOnlyReturning("Inspect", "x".repeat(300)));

        AgentRunResult result = executor(llm, tools, policy).run(
                conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(llm.mainCalls).hasValue(2);
        assertThat(llm.summaryCalls).hasValue(2);
        assertThat(conversation.lastCompaction()).isPresent();
    }

    @Test
    void contextOverflowCompactsOnceAndRetriesSafely() {
        PolicyAwareLlm llm = new PolicyAwareLlm(true);
        Conversation conversation = longConversation("overflow-once");
        ContextPolicy policy = new ContextCompactionService(
                llm, TokenBudget.of(1_000_000), 1);

        AgentRunResult result = executor(llm, new ToolRegistry(), policy)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.CONTEXT_EXHAUSTED);
        assertThat(llm.mainCalls).hasValue(2);
        assertThat(llm.summaryCalls).hasValue(1);
        assertThat(conversation.lastCompaction()).isPresent();
    }

    @Test
    void structuredAndCodingAgentsUseSameContextPolicy() {
        AtomicInteger policyCalls = new AtomicInteger();
        ContextPolicy policy = ContextPolicy.observing(policyCalls::incrementAndGet);
        Conversation first = conversation("structured");
        Conversation second = conversation("coding");

        executor(completed("first"), new ToolRegistry(), policy)
                .run(first, runContext(first)).join();
        executor(completed("second"), new ToolRegistry(), policy)
                .run(second, runContext(second)).join();

        assertThat(policyCalls).hasValue(2);
    }

    private static AgentExecutor executor(
            LlmClient llm, ToolRegistry tools, ContextPolicy policy) {
        return new AgentExecutor(llm, tools, PermissionService.bypassing(),
                policy, ToolOutputPolicy.defaultLimited());
    }

    private static Conversation longConversation(String id) {
        Conversation conversation = conversation(id);
        for (int i = 0; i < 5; i++) {
            conversation.append(UserMessage.of("history-" + i + "-" + "x".repeat(80)));
        }
        return conversation;
    }

    private static Conversation conversation(String id) {
        Conversation conversation = new Conversation(
                com.anthropic.agentkit.domain.conversation.SessionId.of(id));
        conversation.append(UserMessage.of("start"));
        return conversation;
    }

    private static LlmClient completed(String text) {
        return (request, handler) -> LlmCall.start(
                handler, sink -> sink.onComplete(AiMessage.text(text)));
    }

    private static final class PolicyAwareLlm implements LlmClient {
        private final boolean alwaysOverflow;
        private final AtomicInteger mainCalls = new AtomicInteger();
        private final AtomicInteger summaryCalls = new AtomicInteger();

        private PolicyAwareLlm(boolean alwaysOverflow) {
            this.alwaysOverflow = alwaysOverflow;
        }

        @Override
        public LlmCall streamChat(ChatRequest request, StreamHandler handler) {
            if (request.systemPrompt().contains("compress conversation")) {
                summaryCalls.incrementAndGet();
                return LlmCall.start(handler, sink -> sink.onComplete(AiMessage.text("summary")));
            }
            int call = mainCalls.incrementAndGet();
            if (alwaysOverflow) {
                return LlmCall.start(handler, sink -> sink.onError(
                        new ContextWindowExceededException("context_length_exceeded")));
            }
            AiMessage response = call == 1 ? toolRequest() : AiMessage.text("done");
            return LlmCall.start(handler, sink -> sink.onComplete(response));
        }

        private static AiMessage toolRequest() {
            return AiMessage.of("", List.of(new ToolUseRequest(
                    new ToolUseId("inspect-1"), "Inspect", "{}")));
        }
    }
}
