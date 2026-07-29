package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.context.ContextPolicy;
import com.anthropic.agentkit.application.recovery.RunEventProjector;
import com.anthropic.agentkit.application.tool.ToolOutputPolicy;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.RunEventPersistenceException;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.anthropic.agentkit.testsupport.TestRunContexts.runContext;
import static org.assertj.core.api.Assertions.assertThat;

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

    private static AgentExecutor executor(
            StubLlmClient llm, ToolRegistry tools, RunEventStore store) {
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
