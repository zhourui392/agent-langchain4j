package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.ModelIdentity;
import com.anthropic.agentkit.domain.agent.ModelPolicy;
import com.anthropic.agentkit.domain.agent.ModelTier;
import com.anthropic.agentkit.domain.agent.ModelUsage;
import com.anthropic.agentkit.domain.agent.RetryPolicy;
import com.anthropic.agentkit.domain.agent.RunDeadline;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmClientSelector;
import com.anthropic.agentkit.domain.port.ProviderFailureException;
import com.anthropic.agentkit.domain.port.ProviderFailureKind;
import com.anthropic.agentkit.domain.port.RetrySleeper;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.anthropic.agentkit.testsupport.TestRunContexts.runContext;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutorModelPolicyTest {

    private static final ModelIdentity PRIMARY =
            new ModelIdentity("provider-a", "primary-model");
    private static final ModelIdentity FALLBACK =
            new ModelIdentity("provider-b", "fallback-model");

    @Test
    void retriesTransientProviderFailureBeforeAssistantTurn() {
        ScriptedModel model = new ScriptedModel(PRIMARY)
                .fail(transientFailure()).complete("done", 7, 3);
        Conversation conversation = conversation("transient-retry");

        AgentRunResult result = executor(
                fixed(model), retrying(ModelTier.DEFAULT, 2), RetrySleeper.immediate(),
                new ToolRegistry()).run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(model.calls()).isEqualTo(2);
        assertThat(result.consumption().llmCalls()).isEqualTo(2);
        assertThat(conversation.messages()).containsExactly(
                UserMessage.of("inspect"), AiMessage.text("done"));
    }

    @Test
    void doesNotRetryNonTransientAuthenticationFailure() {
        ScriptedModel model = new ScriptedModel(PRIMARY)
                .fail(new ProviderFailureException(
                        ProviderFailureKind.AUTHENTICATION, "invalid API key"))
                .complete("must not run", 1, 1);
        Conversation conversation = conversation("authentication-failure");

        AgentRunResult result = executor(
                fixed(model), retrying(ModelTier.DEFAULT, 3), RetrySleeper.immediate(),
                new ToolRegistry()).run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.PROVIDER_ERROR);
        assertThat(model.calls()).isEqualTo(1);
        assertThat(result.consumption().llmCalls()).isEqualTo(1);
    }

    @Test
    void retryConsumesBudgetAndHonorsDeadline() {
        ScriptedModel budgeted = new ScriptedModel(PRIMARY).fail(transientFailure());
        Conversation budgetConversation = conversation("retry-budget");
        AgentRunContext limited = runContext(budgetConversation,
                new CancellationToken(),
                AgentBudget.unlimited().withMaxLlmCalls(1));

        AgentRunResult exhausted = executor(
                fixed(budgeted), retrying(ModelTier.DEFAULT, 2), RetrySleeper.immediate(),
                new ToolRegistry()).run(budgetConversation, limited).join();

        assertThat(exhausted.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(exhausted.consumption().llmCalls()).isEqualTo(1);
        assertThat(budgeted.calls()).isEqualTo(1);
        assertDeadlineStopsBeforeBackoff();
    }

    @Test
    void neverReplaysSettledToolAfterProviderFailure() {
        ScriptedModel model = new ScriptedModel(PRIMARY)
                .complete(toolTurn(), 4, 1)
                .fail(transientFailure()).complete("done", 3, 2);
        FakeTool write = FakeTool.returning("Write", "changed");
        Conversation conversation = conversation("settled-tool");

        AgentRunResult result = executor(
                fixed(model), retrying(ModelTier.DEFAULT, 2), RetrySleeper.immediate(),
                new ToolRegistry().register(write))
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(model.calls()).isEqualTo(3);
        assertThat(write.callCount()).isOne();
        assertThat(conversation.messages())
                .filteredOn(ToolResultMessage.class::isInstance).hasSize(1);
    }

    @Test
    void recordsActualFallbackModelInUsage() {
        ScriptedModel primary = new ScriptedModel(PRIMARY).fail(transientFailure());
        ScriptedModel fallback = new ScriptedModel(FALLBACK).complete("fallback", 9, 4);
        LlmClientSelector models = tier -> Map.of(
                ModelTier.FAST, primary, ModelTier.CAPABLE, fallback).get(tier);
        ModelPolicy policy = new ModelPolicy(
                ModelTier.FAST, List.of(ModelTier.CAPABLE),
                RetryPolicy.fixed(2, Duration.ZERO));
        Conversation conversation = conversation("fallback-usage");

        AgentRunResult result = executor(
                models, policy, RetrySleeper.immediate(), new ToolRegistry())
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(result.usage().modelUsage()).extracting(ModelUsage::model)
                .containsExactly(PRIMARY, FALLBACK);
        assertThat(result.usage().modelUsage().getLast().outputTokens()).isEqualTo(4);
    }

    private static void assertDeadlineStopsBeforeBackoff() {
        ScriptedModel model = new ScriptedModel(PRIMARY).fail(transientFailure());
        Conversation conversation = conversation("retry-deadline");
        AtomicInteger sleeps = new AtomicInteger();
        RetrySleeper sleeper = delay -> sleeps.incrementAndGet();
        AgentRunContext context = runContext(conversation).withLimits(new AgentRunLimits(
                RunDeadline.after(Duration.ofMillis(100)), Duration.ofSeconds(1),
                Duration.ofSeconds(1)));
        ModelPolicy policy = new ModelPolicy(ModelTier.DEFAULT, List.of(),
                RetryPolicy.fixed(2, Duration.ofSeconds(1)));

        AgentRunResult result = executor(fixed(model), policy, sleeper, new ToolRegistry())
                .run(conversation, context).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.TIMED_OUT);
        assertThat(model.calls()).isEqualTo(1);
        assertThat(sleeps).hasValue(0);
    }

    private static AgentExecutor executor(
            LlmClientSelector models, ModelPolicy policy,
            RetrySleeper sleeper, ToolRegistry tools) {
        return new AgentExecutor(
                models, policy, sleeper, tools, PermissionService.bypassing());
    }

    private static LlmClientSelector fixed(LlmClient client) {
        return ignored -> client;
    }

    private static ModelPolicy retrying(ModelTier tier, int attempts) {
        return new ModelPolicy(
                tier, List.of(), RetryPolicy.fixed(attempts, Duration.ZERO));
    }

    private static ProviderFailureException transientFailure() {
        return new ProviderFailureException(
                ProviderFailureKind.TRANSIENT, "provider unavailable");
    }

    private static Conversation conversation(String id) {
        Conversation conversation = new Conversation(SessionId.of(id));
        conversation.append(UserMessage.of("inspect"));
        return conversation;
    }

    private static AiMessage toolTurn() {
        return AiMessage.of("write", List.of(new ToolUseRequest(
                new ToolUseId("write-1"), "Write", "{}")));
    }

    private static final class ScriptedModel implements LlmClient {
        private final ModelIdentity identity;
        private final Deque<Consumer<StreamHandler>> outcomes = new ArrayDeque<>();
        private int calls;

        private ScriptedModel(ModelIdentity identity) {
            this.identity = identity;
        }

        private ScriptedModel fail(Throwable failure) {
            outcomes.add(handler -> handler.onError(failure));
            return this;
        }

        private ScriptedModel complete(String text, int input, int output) {
            return complete(AiMessage.text(text), input, output);
        }

        private ScriptedModel complete(AiMessage message, int input, int output) {
            outcomes.add(handler -> {
                handler.onUsage(input, output, 0);
                handler.onComplete(message);
            });
            return this;
        }

        @Override
        public LlmCall streamChat(ChatRequest request, StreamHandler handler) {
            calls++;
            Consumer<StreamHandler> outcome = outcomes.removeFirst();
            return LlmCall.start(handler, outcome);
        }

        @Override
        public ModelIdentity modelIdentity() {
            return identity;
        }

        private int calls() {
            return calls;
        }
    }
}
