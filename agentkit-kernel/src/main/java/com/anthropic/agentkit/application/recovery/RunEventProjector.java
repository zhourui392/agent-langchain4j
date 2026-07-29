package com.anthropic.agentkit.application.recovery;

import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.run.RunEventMetadata;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Rebuilds conversation and lifecycle projections from ordered facts only. */
public final class RunEventProjector {

    public RecoveredRun project(List<RunEvent> source) {
        List<RunEvent> events = List.copyOf(source);
        RunEvent.RunStarted started = validateAndStart(events);
        ProjectionState state = new ProjectionState(started);
        for (int index = 1; index < events.size(); index++) {
            state.apply(events.get(index));
        }
        state.reconcileIncompleteBatch();
        return state.result();
    }

    private RunEvent.RunStarted validateAndStart(List<RunEvent> events) {
        if (events.isEmpty() || !(events.getFirst() instanceof RunEvent.RunStarted started)) {
            throw new IllegalArgumentException("run event stream must start with RunStarted");
        }
        RunEventMetadata first = started.metadata();
        long expected = 1;
        for (RunEvent event : events) {
            RunEventMetadata metadata = event.metadata();
            if (!metadata.runId().equals(first.runId())
                    || !metadata.sessionId().equals(first.sessionId())
                    || !metadata.workspaceId().equals(first.workspaceId())) {
                throw new IllegalArgumentException("run event scope changed within stream");
            }
            if (metadata.sequence() != expected++) {
                throw new IllegalArgumentException("run event sequence is not contiguous");
            }
        }
        return started;
    }

    private static final class ProjectionState {
        private final Conversation conversation;
        private final List<RecoveredToolInvocation> invocations = new ArrayList<>();
        private Optional<AgentRunResult> terminalResult = Optional.empty();
        private PendingBatch activeBatch;

        private ProjectionState(RunEvent.RunStarted started) {
            conversation = Conversation.restoreProjection(
                    started.metadata().sessionId(), started.initialMessages(),
                    started.initialCompaction());
        }

        private void apply(RunEvent event) {
            switch (event) {
                case RunEvent.RunStarted ignored ->
                        throw new IllegalArgumentException("duplicate RunStarted event");
                case RunEvent.LlmCallStarted ignored -> { }
                case RunEvent.AssistantTurnReceived received -> receive(received.message());
                case RunEvent.ToolInvocationStarted started -> started(started.toolUseId());
                case RunEvent.ToolInvocationSettled settled -> settled(settled);
                case RunEvent.CompactionCompleted compacted -> compact(compacted);
                case RunEvent.RunStopped stopped -> terminalResult = Optional.of(stopped.toResult());
            }
        }

        private void receive(AiMessage message) {
            if (activeBatch != null) {
                throw new IllegalArgumentException("assistant turn arrived before tool batch settled");
            }
            conversation.append(message);
            if (message.hasToolUseRequests()) {
                activeBatch = new PendingBatch(message.toolUseRequests());
            }
        }

        private void started(ToolUseId toolUseId) {
            requireActive().started(toolUseId);
        }

        private void settled(RunEvent.ToolInvocationSettled event) {
            PendingBatch batch = requireActive();
            batch.settled(event.toolUseId(), event.result());
            if (batch.isFullySettled()) {
                flush(batch, false);
            }
        }

        private void compact(RunEvent.CompactionCompleted event) {
            if (activeBatch != null) {
                throw new IllegalArgumentException("compaction occurred during pending tool batch");
            }
            conversation.installCompaction(event.boundary(), event.retainedMessages());
        }

        private PendingBatch requireActive() {
            if (activeBatch == null) {
                throw new IllegalArgumentException("tool lifecycle event has no active assistant batch");
            }
            return activeBatch;
        }

        private void reconcileIncompleteBatch() {
            if (activeBatch != null) {
                flush(activeBatch, true);
            }
        }

        private void flush(PendingBatch batch, boolean reconcile) {
            for (ToolUseRequest request : batch.requests()) {
                ToolOutcome outcome = batch.outcome(request, reconcile);
                conversation.append(ToolResultMessage.from(request.id(), outcome.result()));
                invocations.add(new RecoveredToolInvocation(
                        request, outcome.status(), Optional.of(outcome.result())));
            }
            activeBatch = null;
        }

        private RecoveredRun result() {
            return new RecoveredRun(conversation, terminalResult, invocations);
        }
    }

    private static final class PendingBatch {
        private final List<ToolUseRequest> requests;
        private final Map<ToolUseId, ToolUseRequest> byId = new LinkedHashMap<>();
        private final Set<ToolUseId> started = new LinkedHashSet<>();
        private final Map<ToolUseId, ToolResult> settled = new LinkedHashMap<>();

        private PendingBatch(List<ToolUseRequest> requests) {
            this.requests = List.copyOf(requests);
            this.requests.forEach(request -> byId.put(request.id(), request));
        }

        private List<ToolUseRequest> requests() {
            return requests;
        }

        private void started(ToolUseId id) {
            requireKnown(id);
            if (!started.add(id)) {
                throw new IllegalArgumentException("duplicate tool start: " + id);
            }
        }

        private void settled(ToolUseId id, ToolResult result) {
            requireKnown(id);
            if (settled.putIfAbsent(id, result) != null) {
                throw new IllegalArgumentException("duplicate tool settlement: " + id);
            }
        }

        private boolean isFullySettled() {
            return settled.size() == requests.size();
        }

        private ToolOutcome outcome(ToolUseRequest request, boolean reconcile) {
            ToolResult result = settled.get(request.id());
            if (result != null) {
                return new ToolOutcome(RecoveryStatus.SETTLED, result);
            }
            if (!reconcile) {
                throw new IllegalStateException("cannot flush incomplete tool batch");
            }
            return started.contains(request.id()) ? unknown() : notStarted();
        }

        private void requireKnown(ToolUseId id) {
            if (!byId.containsKey(id)) {
                throw new IllegalArgumentException("unknown tool use in event stream: " + id);
            }
        }

        private ToolOutcome unknown() {
            return new ToolOutcome(RecoveryStatus.UNKNOWN, ToolResult.of(
                    ToolResultStatus.UNKNOWN,
                    "tool outcome unknown after interrupted run; reconciliation required",
                    Map.of("agentkit.recovery", "needs_reconciliation")));
        }

        private ToolOutcome notStarted() {
            return new ToolOutcome(RecoveryStatus.NOT_STARTED, ToolResult.of(
                    ToolResultStatus.CANCELLED,
                    "tool was not started before interrupted run",
                    Map.of("agentkit.recovery", "not_started")));
        }
    }

    private record ToolOutcome(RecoveryStatus status, ToolResult result) {
    }
}
