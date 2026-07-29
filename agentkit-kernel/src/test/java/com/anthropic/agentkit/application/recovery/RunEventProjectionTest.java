package com.anthropic.agentkit.application.recovery;

import com.anthropic.agentkit.domain.agent.AgentUsage;
import com.anthropic.agentkit.domain.agent.BudgetConsumption;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CompactionBoundary;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.run.RunEventMetadata;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RunEventProjectionTest {

    private static final RunId RUN = RunId.of("recover-run");
    private static final ToolUseId TOOL_USE = new ToolUseId("read-1");

    @Test
    void rebuildsConversationProjectionFromEvents() {
        List<RunEvent> events = completedToolRun();

        RecoveredRun recovered = new RunEventProjector().project(events);

        assertThat(recovered.conversation().messages())
                .extracting(message -> message.text())
                .containsExactly("inspect", "calling", "file body", "finished");
        assertThat(recovered.invocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.status()).isEqualTo(RecoveryStatus.SETTLED);
            assertThat(invocation.result()).contains(ToolResult.ok("file body"));
        });
    }

    @Test
    void resumeDoesNotReexecuteSettledToolInvocation() {
        MemoryRunEventStore store = new MemoryRunEventStore(completedToolRun());
        FakeTool read = FakeTool.readOnlyReturning("Read", "executed again");

        RecoveredRun recovered = new RunEventResumer(store).resume(RUN);

        assertThat(read.callCount()).isZero();
        assertThat(recovered.invocations()).singleElement()
                .extracting(RecoveredToolInvocation::status)
                .isEqualTo(RecoveryStatus.SETTLED);
    }

    @Test
    void resumeMarksStartedButUnsettledInvocationAsUnknown() {
        List<RunEvent> events = List.of(
                started(1, List.of(UserMessage.of("inspect"))),
                new RunEvent.AssistantTurnReceived(metadata(2), toolTurn()),
                new RunEvent.ToolInvocationStarted(metadata(3), TOOL_USE));

        RecoveredRun recovered = new RunEventProjector().project(events);

        ToolResultMessage result = (ToolResultMessage) recovered.conversation()
                .messages().getLast();
        assertThat(result.status()).isEqualTo(ToolResultStatus.UNKNOWN);
        assertThat(result.metadata()).containsEntry("agentkit.recovery", "needs_reconciliation");
        assertThat(recovered.invocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.status()).isEqualTo(RecoveryStatus.UNKNOWN);
            assertThat(invocation.result().orElseThrow().status()).isEqualTo(ToolResultStatus.UNKNOWN);
        });
    }

    @Test
    void terminalPayloadAndStopReasonSurviveResume() {
        AiMessage terminal = AiMessage.of("", List.of(new ToolUseRequest(
                new ToolUseId("submit-1"), "submit_plan", "{\"plan\":\"fix it\"}")));
        ToolResult accepted = ToolResult.ok("accepted");
        List<RunEvent> events = List.of(
                started(1, List.of(UserMessage.of("plan"))),
                new RunEvent.AssistantTurnReceived(metadata(2), terminal),
                new RunEvent.ToolInvocationStarted(metadata(3), new ToolUseId("submit-1")),
                new RunEvent.ToolInvocationSettled(
                        metadata(4), new ToolUseId("submit-1"), accepted),
                stopped(5, StopReason.TERMINAL_TOOL, terminal,
                        Optional.of(Map.of("plan", "fix it"))));

        RecoveredRun recovered = new RunEventProjector().project(events);

        assertThat(recovered.terminalResult()).get().satisfies(result -> {
            assertThat(result.stopReason()).isEqualTo(StopReason.TERMINAL_TOOL);
            assertThat(result.structuredOutput()).contains(Map.of("plan", "fix it"));
            assertThat(result.usage()).isEqualTo(new AgentUsage(11, 4, 2));
        });
    }

    @Test
    void compactionBoundarySurvivesProjectionRebuild() {
        UserMessage old = UserMessage.of("old history");
        UserMessage recent = UserMessage.of("recent question");
        CompactionBoundary boundary = new CompactionBoundary(
                0, 1, 9, 2, "older facts");
        List<RunEvent> events = List.of(
                started(1, List.of(old, recent)),
                new RunEvent.CompactionCompleted(metadata(2), boundary, List.of(recent)));

        RecoveredRun recovered = new RunEventProjector().project(events);

        assertThat(recovered.conversation().lastCompaction()).contains(boundary);
        assertThat(recovered.conversation().messages())
                .containsExactly(boundary.asMessage(), recent);
    }

    private static List<RunEvent> completedToolRun() {
        AiMessage finalMessage = AiMessage.text("finished");
        return List.of(
                started(1, List.of(UserMessage.of("inspect"))),
                new RunEvent.AssistantTurnReceived(metadata(2), toolTurn()),
                new RunEvent.ToolInvocationStarted(metadata(3), TOOL_USE),
                new RunEvent.ToolInvocationSettled(metadata(4), TOOL_USE, ToolResult.ok("file body")),
                new RunEvent.AssistantTurnReceived(metadata(5), finalMessage),
                stopped(6, StopReason.MODEL_COMPLETED, finalMessage, Optional.empty()));
    }

    private static RunEvent.RunStarted started(
            long sequence, List<com.anthropic.agentkit.domain.message.ChatMessage> messages) {
        return new RunEvent.RunStarted(metadata(sequence), messages, Optional.empty());
    }

    private static AiMessage toolTurn() {
        return AiMessage.of("calling", List.of(new ToolUseRequest(
                TOOL_USE, "Read", "{\"path\":\"a.txt\"}")));
    }

    private static RunEvent.RunStopped stopped(
            long sequence, StopReason reason, AiMessage finalMessage,
            Optional<Map<String, Object>> payload) {
        return new RunEvent.RunStopped(
                metadata(sequence), reason, finalMessage, payload,
                new AgentUsage(11, 4, 2), new BudgetConsumption(2, 1, 11, 4, 30),
                Optional.empty());
    }

    private static RunEventMetadata metadata(long sequence) {
        return new RunEventMetadata(
                RunEvent.CURRENT_SCHEMA_VERSION, RUN, SessionId.of("recover-session"),
                WorkspaceId.of("recover-workspace"), sequence,
                Instant.parse("2026-07-29T01:00:00Z").plusSeconds(sequence));
    }

    private static final class MemoryRunEventStore implements RunEventStore {
        private final List<RunEvent> events;

        private MemoryRunEventStore(List<RunEvent> events) {
            this.events = new ArrayList<>(events);
        }

        @Override public void append(RunEvent event) { events.add(event); }
        @Override public List<RunEvent> load(RunId runId) { return List.copyOf(events); }
    }
}
