package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
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
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.anthropic.agentkit.testsupport.TestRunContexts.runContext;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutorBoundedOperationsTest {

    @Test
    void cancelsLlmCallBeforeFirstDelta() throws Exception {
        HangingLlmClient llm = new HangingLlmClient();
        Conversation conversation = conversation("cancel-before-delta");
        CancellationToken cancellation = new CancellationToken();
        AgentExecutor executor = new AgentExecutor(llm, new ToolRegistry(), allowAll());

        var running = executor.run(conversation, runContext(conversation, cancellation));
        assertThat(llm.started.await(1, TimeUnit.SECONDS)).isTrue();
        cancellation.cancel();

        assertThat(running.get(1, TimeUnit.SECONDS).stopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(llm.call.cancelled).isTrue();
    }

    @Test
    void timeoutCancelsProviderRequest() throws Exception {
        HangingLlmClient llm = new HangingLlmClient();
        Conversation conversation = conversation("provider-timeout");
        AgentRunContext context = runContext(conversation).withLimits(limits(Duration.ofMillis(30)));

        AgentRunResult result = new AgentExecutor(llm, new ToolRegistry(), allowAll())
                .run(conversation, context).get(1, TimeUnit.SECONDS);

        assertThat(result.stopReason()).isEqualTo(StopReason.TIMED_OUT);
        assertThat(llm.call.cancelled).isTrue();
    }

    @Test
    void lateCompletionAfterTimeoutIsIgnored() throws Exception {
        HangingLlmClient llm = new HangingLlmClient();
        Conversation conversation = conversation("late-completion");
        AgentRunContext context = runContext(conversation).withLimits(limits(Duration.ofMillis(30)));
        AtomicInteger observedUsage = new AtomicInteger();
        AgentEventListener listener = new AgentEventListener() {
            @Override public void onUsage(int input, int output, int cacheRead) {
                observedUsage.addAndGet(output);
            }
        };

        AgentRunResult result = new AgentExecutor(llm, new ToolRegistry(), allowAll())
                .run(conversation, context, listener).get(1, TimeUnit.SECONDS);
        llm.completeLate(AiMessage.text("too late"), 17);

        assertThat(result.stopReason()).isEqualTo(StopReason.TIMED_OUT);
        assertThat(result.usage().outputTokens()).isZero();
        assertThat(observedUsage).hasValue(0);
        assertThat(conversation.messages()).noneMatch(AiMessage.class::isInstance);
    }

    @Test
    void outputTokenBudgetStopsRunWithExplicitReason() {
        LlmClient llm = (request, handler) -> LlmCall.start(handler, sink -> {
            sink.onUsage(5, 4, 0);
            sink.onComplete(AiMessage.text("too verbose"));
        });
        Conversation conversation = conversation("output-budget");
        AgentBudget budget = AgentBudget.of(3, 3, 100, 3, 1_000);

        AgentRunResult result = new AgentExecutor(llm, new ToolRegistry(), allowAll())
                .run(conversation, runContext(conversation, new CancellationToken(), budget)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(result.usage().outputTokens()).isEqualTo(4);
        assertThat(result.consumption().outputTokens()).isEqualTo(4);
    }

    @Test
    void toolTimeoutSettlesInvocationAsTimeout() {
        LlmClient llm = new SequencedLlmClient(
                toolMessage("slow-1", "Slow"), AiMessage.text("recovered"));
        Conversation conversation = conversation("tool-timeout");
        AgentRunContext context = runContext(conversation)
                .withLimits(new AgentRunLimits(
                        RunDeadline.after(Duration.ofSeconds(2)),
                        Duration.ofSeconds(1), Duration.ofMillis(30)));

        AgentRunResult result = new AgentExecutor(
                llm, new ToolRegistry().register(interruptibleSlowTool()), allowAll())
                .run(conversation, context).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(conversation.messages()).filteredOn(ToolResultMessage.class::isInstance)
                .extracting(message -> ((ToolResultMessage) message).status())
                .containsExactly(ToolResultStatus.TIMEOUT);
    }

    @Test
    void childAgentCannotExceedParentDeadlineOrBudget() {
        LlmClient llm = new SequencedLlmClient(
                toolMessage("inspect-1", "Inspect"), AiMessage.text("must not be requested"));
        Conversation conversation = conversation("child-budget");
        AgentBudget budget = AgentBudget.of(1, 10, 1_000, 1_000, 10_000);
        AgentRunContext parent = runContext(conversation, new CancellationToken(), budget);
        com.anthropic.agentkit.infrastructure.tools.SubAgentTool child =
                new com.anthropic.agentkit.infrastructure.tools.SubAgentTool(
                        llm, new ToolRegistry().register(immediateTool("Inspect")));

        ToolResult result = child.execute(
                ToolArguments.of(java.util.Map.of("prompt", "inspect")), parent.executionContext());

        assertThat(result.success()).isTrue();
        assertThat(((SequencedLlmClient) llm).calls).hasValue(1);
        assertThat(parent.budgetConsumption().turns()).isEqualTo(1);
    }

    private static AgentRunLimits limits(Duration timeout) {
        return new AgentRunLimits(RunDeadline.after(timeout), timeout, timeout);
    }

    private static Conversation conversation(String id) {
        Conversation conversation = new Conversation(SessionId.of(id));
        conversation.append(UserMessage.of("start"));
        return conversation;
    }

    private static AiMessage toolMessage(String id, String name) {
        return AiMessage.of("", List.of(new ToolUseRequest(new ToolUseId(id), name, "{}")));
    }

    private static Tool interruptibleSlowTool() {
        return new Tool() {
            @Override public String name() { return "Slow"; }
            @Override public String description() { return "waits"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean isReadOnly() { return true; }
            @Override public ToolResult execute(ToolArguments args,
                                                com.anthropic.agentkit.domain.tool.ExecutionContext ctx) {
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                    return ToolResult.ok("late");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return ToolResult.of(ToolResultStatus.CANCELLED, "interrupted");
                }
            }
        };
    }

    private static Tool immediateTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "immediate"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean isReadOnly() { return true; }
            @Override public ToolResult execute(ToolArguments args,
                                                com.anthropic.agentkit.domain.tool.ExecutionContext ctx) {
                return ToolResult.ok("ok");
            }
        };
    }

    private static PermissionService allowAll() {
        return new PermissionService((invocation, tool, mode) ->
                com.anthropic.agentkit.domain.permission.Decision.ALLOW,
                (invocation, tool) -> { throw new IllegalStateException("not used"); },
                com.anthropic.agentkit.domain.permission.PermissionMode.BYPASS);
    }

    private static final class HangingLlmClient implements LlmClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final ManualCall call = new ManualCall();
        private StreamHandler handler;

        @Override
        public LlmCall streamChat(ChatRequest request, StreamHandler handler) {
            this.handler = handler;
            started.countDown();
            return call;
        }

        private void completeLate(AiMessage message, int outputTokens) {
            handler.onUsage(0, outputTokens, 0);
            handler.onComplete(message);
            call.completion.complete(message);
        }
    }

    private static final class ManualCall implements LlmCall {
        private final CompletableFuture<AiMessage> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override public CompletionStage<AiMessage> completion() { return completion; }
        @Override public boolean cancel() {
            boolean won = cancelled.compareAndSet(false, true);
            if (won) {
                completion.cancel(false);
            }
            return won;
        }
    }

    private static final class SequencedLlmClient implements LlmClient {
        private final List<AiMessage> responses;
        private final AtomicInteger calls = new AtomicInteger();

        private SequencedLlmClient(AiMessage... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public LlmCall streamChat(ChatRequest request, StreamHandler handler) {
            AiMessage response = responses.get(calls.getAndIncrement());
            return LlmCall.start(handler, sink -> sink.onComplete(response));
        }
    }
}
