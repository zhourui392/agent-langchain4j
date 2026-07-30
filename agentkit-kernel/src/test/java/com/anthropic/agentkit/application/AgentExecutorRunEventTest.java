package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.context.ContextPolicy;
import com.anthropic.agentkit.application.context.ContextCompactionService;
import com.anthropic.agentkit.application.recovery.RunEventProjector;
import com.anthropic.agentkit.application.tool.ToolOutputPolicy;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.conversation.TokenBudget;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.RunEventPersistenceException;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolSideEffect;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.anthropic.agentkit.testsupport.TestRunContexts.runContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutorRunEventTest {

    @Test
    void executorPersistsFactsSeparatelyFromListenerProjection() {
        MemoryStore store = new MemoryStore();
        FakeTool read = FakeTool.readOnlyReturning("Read", "body");
        ToolRegistry tools = new ToolRegistry().register(read);
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolTurn()).enqueue(AiMessage.text("done"));
        Conversation conversation = conversation("event-integration");

        AgentRunResult result = executor(llm, tools, store)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(store.events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("RunStarted", "LlmCallStarted", "AssistantTurnReceived",
                        "ToolInvocationStarted", "ToolInvocationSettled", "LlmCallStarted",
                        "AssistantTurnReceived", "RunStopped");
        assertThat(new RunEventProjector().project(store.events).conversation().messages())
                .containsExactlyElementsOf(conversation.messages());
    }

    @Test
    void eventStoreFailureStopsBeforeUnrecordedExternalEffect() {
        FailingStore store = new FailingStore(4);
        FakeTool write = FakeTool.returning("Write", "changed");
        StubLlmClient llm = new StubLlmClient().enqueue(toolTurn("Write"));
        Conversation conversation = conversation("event-failure");

        AgentRunResult result = executor(
                llm, new ToolRegistry().register(write), store)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.PERSISTENCE_ERROR);
        assertThat(write.callCount()).isZero();
        assertThat(store.events).hasSize(3);
    }

    @Test
    void mutatingToolPersistsNonReversibleSideEffectFact() {
        MemoryStore store = new MemoryStore();
        FakeTool write = FakeTool.returning("Write", "changed");
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolTurn("Write")).enqueue(AiMessage.text("done"));
        Conversation conversation = conversation("side-effect-event");

        AgentRunResult result = executor(
                llm, new ToolRegistry().register(write), store)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(store.events)
                .filteredOn(RunEvent.ToolSideEffectObserved.class::isInstance)
                .singleElement().satisfies(event -> {
                    ToolSideEffect effect = ((RunEvent.ToolSideEffectObserved) event).sideEffect();
                    assertThat(effect).isEqualTo(new ToolSideEffect.NonReversible(
                            "Write", "external side effects are not reversible"));
                });
    }

    @Test
    void listenerFailureDoesNotPreventRunStoppedFact() {
        MemoryStore store = new MemoryStore();
        FakeTool read = FakeTool.readOnlyReturning("Read", "body");
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolTurn()).enqueue(AiMessage.text("done"));
        Conversation conversation = conversation("broken-listener");

        AgentRunResult result = executor(llm, new ToolRegistry().register(read), store)
                .run(conversation, runContext(conversation), failingListener()).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(read.callCount()).isOne();
        assertThat(store.events.getLast()).isInstanceOf(RunEvent.RunStopped.class);
    }

    @Test
    void requiredListenerFailureFailsTheRun() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        Conversation conversation = conversation("required-listener");
        RequiredAgentEventListener listener = new RequiredAgentEventListener() {
            @Override
            public void onTurnComplete(AiMessage finalMessage) {
                throw new IllegalStateException("required projection failed");
            }
        };

        assertThatThrownBy(() -> executor(llm, new ToolRegistry(), new MemoryStore())
                .run(conversation, runContext(conversation), listener).join())
                .hasRootCauseMessage("required projection failed");
    }

    @Test
    void executorPersistsCompactionCompleted() {
        MemoryStore store = new MemoryStore();
        CompactingLlm llm = new CompactingLlm();
        Conversation conversation = longConversation("compaction-event");
        ContextPolicy policy = new ContextCompactionService(llm, TokenBudget.of(40), 1);
        AgentExecutor executor = new AgentExecutor(
                llm, new ToolRegistry(), PermissionService.bypassing(), policy,
                ToolOutputPolicy.defaultLimited(), store);

        AgentRunResult result = executor.run(
                conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(store.events).anyMatch(RunEvent.CompactionCompleted.class::isInstance);
        assertThat(new RunEventProjector().project(store.events).conversation().messages())
                .containsExactlyElementsOf(conversation.messages());
    }

    private static AgentExecutor executor(
            LlmClient llm, ToolRegistry tools, RunEventStore store) {
        return new AgentExecutor(
                llm, tools, PermissionService.bypassing(), ContextPolicy.none(),
                ToolOutputPolicy.defaultLimited(), store);
    }

    private static Conversation conversation(String session) {
        Conversation conversation = new Conversation(SessionId.of(session));
        conversation.append(UserMessage.of("inspect"));
        return conversation;
    }

    private static AiMessage toolTurn() {
        return toolTurn("Read");
    }

    private static AiMessage toolTurn(String name) {
        return AiMessage.of("calling", List.of(new ToolUseRequest(
                new ToolUseId("tool-1"), name, "{}")));
    }

    private static Conversation longConversation(String session) {
        Conversation conversation = conversation(session);
        for (int index = 0; index < 5; index++) {
            conversation.append(UserMessage.of("history-" + index + "-" + "x".repeat(80)));
        }
        return conversation;
    }

    private static AgentEventListener failingListener() {
        return new AgentEventListener() {
            @Override public void onRunStart(
                    AgentRunContext context) { fail(); }
            @Override public void onLlmRequestStart() { fail(); }
            @Override public void onAssistantTextDelta(String delta) { fail(); }
            @Override public void onToolUseStart(ToolUseRequest request) { fail(); }
            @Override public void onToolUseEnd(ToolUseRequest request,
                                                ToolResult result,
                                                long durationMs) { fail(); }
            @Override public void onTurnComplete(AiMessage finalMessage) { fail(); }
            private void fail() { throw new IllegalStateException("observer unavailable"); }
        };
    }

    private static final class CompactingLlm implements LlmClient {
        @Override
        public LlmCall streamChat(ChatRequest request, StreamHandler handler) {
            String text = request.systemPrompt().contains("compress conversation")
                    ? "durable summary" : "done";
            return LlmCall.start(handler, sink -> sink.onComplete(AiMessage.text(text)));
        }
    }

    private static class MemoryStore implements RunEventStore {
        protected final List<RunEvent> events = new ArrayList<>();

        @Override public void append(RunEvent event) { events.add(event); }

        @Override
        public List<RunEvent> load(RunId runId) {
            return events.stream()
                    .filter(event -> event.metadata().runId().equals(runId)).toList();
        }
    }

    private static final class FailingStore extends MemoryStore {
        private final long failingSequence;

        private FailingStore(long failingSequence) {
            this.failingSequence = failingSequence;
        }

        @Override
        public void append(RunEvent event) {
            if (event.metadata().sequence() == failingSequence) {
                throw new RunEventPersistenceException("disk unavailable", null);
            }
            super.append(event);
        }
    }
}
