package com.anthropic.agentkit.infrastructure.agent;

import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentRunState;
import com.anthropic.agentkit.domain.agent.AgentSpec;
import com.anthropic.agentkit.domain.agent.ModelTier;
import com.anthropic.agentkit.domain.agent.RunDeadline;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.agent.SubAgentExecutionScope;
import com.anthropic.agentkit.domain.agent.SubAgentHandle;
import com.anthropic.agentkit.domain.agent.SubAgentLimitExceededException;
import com.anthropic.agentkit.domain.agent.SubAgentLimits;
import com.anthropic.agentkit.domain.agent.SubAgentRuntime;
import com.anthropic.agentkit.domain.agent.TerminalToolSpec;
import com.anthropic.agentkit.domain.agent.ToolCapabilitySet;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmClientSelector;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.tools.SubAgentTool;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSubAgentRuntimeTest {

    @Test
    void childCannotUseToolOutsideCapabilitySet() {
        FakeTool write = FakeTool.returning("Write", "changed");
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolRequest("write-1", "Write", "{}"))
                .enqueue(AiMessage.text("write was unavailable"));
        SubAgentRuntime runtime = runtime(llm, registry(write), new SubAgentLimits(2, 2));

        AgentRunResult result = runtime.spawn(
                spec("reader", ToolCapabilitySet.none()), "do not write", parent()).result()
                .toCompletableFuture().join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(write.callCount()).isZero();
        assertThat(llm.capturedRequests().getFirst().tools()).isEmpty();
    }

    @Test
    void rejectsCapabilityThatParentDidNotGrant() {
        SubAgentRuntime runtime = runtime(
                new StubLlmClient(), new ToolRegistry(), new SubAgentLimits(2, 2));

        assertThatThrownBy(() -> runtime.spawn(
                spec("writer", ToolCapabilitySet.of("Write")), "write", parent()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Write");
    }

    @Test
    void childBudgetCountsAgainstParent() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(AiMessage.text("first"))
                .enqueue(AiMessage.text("second"))
                .enqueue(AiMessage.text("must not be accepted"));
        AgentRunContext parent = parent(AgentBudget.of(2, 10, 10_000));
        SubAgentRuntime runtime = runtime(llm, new ToolRegistry(), new SubAgentLimits(2, 3));

        runtime.spawn(spec("worker", ToolCapabilitySet.none()), "one", parent.executionContext())
                .result().toCompletableFuture().join();
        runtime.spawn(spec("worker", ToolCapabilitySet.none()), "two", parent.executionContext())
                .result().toCompletableFuture().join();
        AgentRunResult exhausted = runtime.spawn(
                        spec("worker", ToolCapabilitySet.none()), "three", parent.executionContext())
                .result().toCompletableFuture().join();

        assertThat(parent.budgetConsumption().turns()).isEqualTo(2);
        assertThat(exhausted.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(llm.capturedRequests()).hasSize(2);
    }

    @Test
    void siblingChildrenHaveLocalBudgetsAndOneSharedParentLedger() {
        SequencedLlmClient llm = new SequencedLlmClient(
                AiMessage.text("first"), AiMessage.text("second"));
        AgentRunContext parent = parent(AgentBudget.of(2, 10, 10_000));
        SubAgentRuntime runtime = runtime(llm, new ToolRegistry(), new SubAgentLimits(2, 2));
        AgentSpec bounded = new AgentSpec(
                AgentId.of("bounded"), "Complete one bounded turn.", ToolCapabilitySet.none(),
                ModelTier.DEFAULT, AgentBudget.of(1, 1, 1_000),
                AgentRunLimits.defaults(), Optional.empty());

        AgentRunResult first = runtime.spawn(bounded, "one", parent).result()
                .toCompletableFuture().join();
        AgentRunResult second = runtime.spawn(bounded, "two", parent).result()
                .toCompletableFuture().join();

        assertThat(first.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(second.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(first.consumption().turns()).isEqualTo(1);
        assertThat(second.consumption().turns()).isEqualTo(1);
        assertThat(parent.budgetConsumption().turns()).isEqualTo(2);
    }

    @Test
    void childDeadlineCannotExceedParent() {
        HangingLlmClient llm = new HangingLlmClient();
        AgentRunContext parent = parent().withLimits(new AgentRunLimits(
                RunDeadline.after(Duration.ofMillis(500)),
                Duration.ofSeconds(2), Duration.ofSeconds(2)));
        SubAgentRuntime runtime = runtime(llm, new ToolRegistry(), new SubAgentLimits(2, 2));

        AgentRunResult result = runtime.spawn(
                spec("slow", ToolCapabilitySet.none()), "wait", parent.executionContext())
                .result().toCompletableFuture().join();

        assertThat(result.stopReason()).isEqualTo(StopReason.TIMED_OUT);
        assertThat(llm.call.cancelled).isTrue();
    }

    @Test
    void rejectsSpawnBeyondDepthLimit() {
        SubAgentLimits limits = new SubAgentLimits(1, 2);
        SubAgentRuntime runtime = runtime(new StubLlmClient(), new ToolRegistry(), limits);
        ExecutionContext atLimit = parent().executionContext().withSubAgentScope(
                SubAgentExecutionScope.root(limits).child(limits));

        assertThatThrownBy(() -> runtime.spawn(
                spec("nested", ToolCapabilitySet.none()), "too deep", atLimit))
                .isInstanceOf(SubAgentLimitExceededException.class)
                .hasMessageContaining("depth");
    }

    @Test
    void rejectsSpawnBeyondConcurrencyLimit() throws Exception {
        HangingLlmClient llm = new HangingLlmClient();
        SubAgentRuntime runtime = runtime(llm, new ToolRegistry(), new SubAgentLimits(2, 1));
        ExecutionContext parent = parent().executionContext();
        SubAgentHandle first = runtime.spawn(
                spec("slow", ToolCapabilitySet.none()), "first", parent);
        assertThat(llm.started.await(1, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> runtime.spawn(
                spec("slow", ToolCapabilitySet.none()), "second", parent))
                .isInstanceOf(SubAgentLimitExceededException.class)
                .hasMessageContaining("concurrency");

        assertThat(first.cancel()).isTrue();
        assertThat(first.result().toCompletableFuture().join().stopReason())
                .isEqualTo(StopReason.CANCELLED);
    }

    @Test
    void followUpTargetsExistingChildConversation() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(AiMessage.text("initial finding"))
                .enqueue(AiMessage.text("refined finding"));
        SubAgentRuntime runtime = runtime(llm, new ToolRegistry(), new SubAgentLimits(2, 1));
        SubAgentHandle handle = runtime.spawn(
                spec("researcher", ToolCapabilitySet.none()), "investigate", parent());
        AgentRunResult first = handle.result().toCompletableFuture().join();

        AgentRunResult second = handle.followUp("check the timestamps")
                .toCompletableFuture().join();

        assertThat(first.runId()).isNotEqualTo(second.runId());
        assertThat(handle.childRunId()).isEqualTo(second.runId());
        assertThat(llm.capturedRequests().get(1).messages())
                .extracting(message -> message.text())
                .containsExactly("investigate", "initial finding", "check the timestamps");
        assertThat(llm.capturedRequests().get(1).messages().getLast())
                .isInstanceOf(UserMessage.class);
    }

    @Test
    void cancelPropagatesToChildLlmAndTools() throws Exception {
        assertParentCancellationStopsLlm();
        assertParentCancellationStopsTool();
    }

    @Test
    void childHandleCancelDoesNotCancelParent() throws Exception {
        HangingLlmClient llm = new HangingLlmClient();
        SubAgentRuntime runtime = runtime(llm, new ToolRegistry(), new SubAgentLimits(2, 1));
        AgentRunContext parent = parent();
        SubAgentHandle handle = runtime.spawn(
                spec("child", ToolCapabilitySet.none()), "wait", parent.executionContext());
        assertThat(llm.started.await(1, TimeUnit.SECONDS)).isTrue();

        handle.cancel();
        AgentRunResult result = handle.result().toCompletableFuture().join();

        assertThat(result.stopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(handle.state()).isEqualTo(AgentRunState.CANCELLED);
        assertThat(parent.cancellation().isCancelled()).isFalse();
    }

    @Test
    void terminalPayloadSurvivesSubAgentBoundary() {
        StubLlmClient llm = new StubLlmClient().enqueue(toolRequest(
                "terminal-1", "submit_finding", "{\"summary\":\"pool exhausted\"}"));
        SubAgentRuntime runtime = runtime(llm, new ToolRegistry(), new SubAgentLimits(2, 1));
        AgentSpec spec = spec("researcher", ToolCapabilitySet.none(), Optional.of(
                new TerminalToolSpec("submit_finding", "Submit finding", """
                        {"type":"object","properties":{"summary":{"type":"string"}},
                         "required":["summary"]}
                        """)));

        AgentRunResult result = runtime.spawn(spec, "investigate", parent()).result()
                .toCompletableFuture().join();

        assertThat(result.stopReason()).isEqualTo(StopReason.TERMINAL_TOOL);
        assertThat(result.structuredOutput()).contains(
                Map.<String, Object>of("summary", "pool exhausted"));
    }

    @Test
    void selectsLlmByModelTier() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        AtomicReference<ModelTier> selected = new AtomicReference<>();
        LlmClientSelector selector = tier -> {
            selected.set(tier);
            return llm;
        };
        SubAgentRuntime runtime = new DefaultSubAgentRuntime(
                selector, new ToolRegistry(), PermissionService.bypassing(),
                new SubAgentLimits(2, 1));
        AgentSpec spec = new AgentSpec(
                AgentId.of("cheap-worker"), "Work efficiently.", ToolCapabilitySet.none(),
                ModelTier.FAST, AgentBudget.unlimited(), AgentRunLimits.defaults(), Optional.empty());

        runtime.spawn(spec, "do it", parent()).result().toCompletableFuture().join();

        assertThat(selected.get()).isEqualTo(ModelTier.FAST);
    }

    @Test
    void toolAdapterHasConfigurableIdentityAndConservativeWriteClassification() {
        SubAgentRuntime runtime = runtime(
                new StubLlmClient(), new ToolRegistry(), new SubAgentLimits(2, 1));
        AgentSpec spec = spec("researcher", ToolCapabilitySet.none());

        SubAgentTool tool = new SubAgentTool(
                "Research", "Delegate bounded research", runtime, spec, false);

        assertThat(tool.name()).isEqualTo("Research");
        assertThat(tool.description()).isEqualTo("Delegate bounded research");
        assertThat(tool.isReadOnly()).isFalse();
    }

    private static void assertParentCancellationStopsLlm() throws Exception {
        HangingLlmClient llm = new HangingLlmClient();
        SubAgentRuntime runtime = runtime(llm, new ToolRegistry(), new SubAgentLimits(2, 1));
        AgentRunContext parent = parent();
        SubAgentHandle handle = runtime.spawn(
                spec("llm-child", ToolCapabilitySet.none()), "wait", parent.executionContext());
        assertThat(llm.started.await(1, TimeUnit.SECONDS)).isTrue();

        parent.cancellation().cancel();

        assertThat(handle.result().toCompletableFuture().join().stopReason())
                .isEqualTo(StopReason.CANCELLED);
        assertThat(llm.call.cancelled).isTrue();
    }

    private static void assertParentCancellationStopsTool() throws Exception {
        BlockingTool tool = new BlockingTool();
        SequencedLlmClient llm = new SequencedLlmClient(
                toolRequest("block-1", "Block", "{}"), AiMessage.text("must not continue"));
        SubAgentRuntime runtime = runtime(llm, registry(tool), new SubAgentLimits(2, 1));
        AgentRunContext parent = parent();
        SubAgentHandle handle = runtime.spawn(
                spec("tool-child", ToolCapabilitySet.of("Block")),
                "run tool", parent.executionContext());
        assertThat(tool.started.await(1, TimeUnit.SECONDS)).isTrue();

        parent.cancellation().cancel();

        assertThat(handle.result().toCompletableFuture().join().stopReason())
                .isEqualTo(StopReason.CANCELLED);
        assertThat(tool.cancelled.get()).isTrue();
    }

    private static DefaultSubAgentRuntime runtime(
            LlmClient llm, ToolRegistry tools, SubAgentLimits limits) {
        return new DefaultSubAgentRuntime(
                LlmClientSelector.fixed(llm), tools, PermissionService.bypassing(), limits);
    }

    private static AgentSpec spec(String id, ToolCapabilitySet tools) {
        return spec(id, tools, Optional.empty());
    }

    private static AgentSpec spec(
            String id, ToolCapabilitySet tools, Optional<TerminalToolSpec> terminal) {
        return new AgentSpec(
                AgentId.of(id), "Complete the delegated task.", tools,
                ModelTier.DEFAULT, AgentBudget.unlimited(), AgentRunLimits.defaults(), terminal);
    }

    private static AgentRunContext parent() {
        return parent(AgentBudget.unlimited());
    }

    private static AgentRunContext parent(AgentBudget budget) {
        return AgentRunContext.create(
                com.anthropic.agentkit.domain.conversation.SessionId.fresh(),
                Path.of("."), new CancellationToken(), budget);
    }

    private static ToolRegistry registry(Tool tool) {
        return new ToolRegistry().register(tool);
    }

    private static AiMessage toolRequest(String id, String name, String args) {
        return new AiMessage("", List.of(new ToolUseRequest(new ToolUseId(id), name, args)));
    }

    private static final class HangingLlmClient implements LlmClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final ManualCall call = new ManualCall();

        @Override
        public LlmCall streamChat(ChatRequest request, StreamHandler handler) {
            started.countDown();
            return call;
        }
    }

    private static final class ManualCall implements LlmCall {
        private final CompletableFuture<AiMessage> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public CompletionStage<AiMessage> completion() {
            return completion;
        }

        @Override
        public boolean cancel() {
            boolean won = cancelled.compareAndSet(false, true);
            if (won) {
                completion.cancel(false);
            }
            return won;
        }
    }

    private static final class SequencedLlmClient implements LlmClient {
        private final List<AiMessage> responses;
        private int index;

        private SequencedLlmClient(AiMessage... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public synchronized LlmCall streamChat(ChatRequest request, StreamHandler handler) {
            AiMessage response = responses.get(index++);
            return LlmCall.start(handler, sink -> sink.onComplete(response));
        }
    }

    private static final class BlockingTool implements Tool {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override public String name() { return "Block"; }
        @Override public String description() { return "Wait until cancelled"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public ToolResult execute(ToolArguments args, ExecutionContext context) {
            started.countDown();
            try {
                while (!context.cancellation().isCancelled()) {
                    Thread.sleep(10);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            cancelled.set(context.cancellation().isCancelled() || Thread.currentThread().isInterrupted());
            return ToolResult.error("cancelled");
        }
    }
}
