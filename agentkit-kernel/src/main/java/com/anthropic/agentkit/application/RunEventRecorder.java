package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.conversation.CompactionBoundary;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.port.RunEventPersistenceException;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.run.RunEventMetadata;
import com.anthropic.agentkit.domain.suspension.ApprovalDecision;
import com.anthropic.agentkit.domain.suspension.InputAnswer;
import com.anthropic.agentkit.domain.suspension.RunSuspension;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolSideEffect;
import com.anthropic.agentkit.domain.tool.ToolUseId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Required fact writer for one configured run; sequencing is independent from observers. */
interface RunEventRecorder {

    RunEventRecorder NO_OP = new RunEventRecorder() { };

    static RunEventRecorder forRun(RunEventStore store, AgentRunContext context) {
        return new PersistingRunEventRecorder(store, context);
    }

    static RunEventRecorder disabled() {
        return NO_OP;
    }

    default void runStarted(Conversation conversation) { }

    default void llmCallStarted(int messageCount) { }

    default void assistantTurnReceived(AiMessage message) { }

    default void toolInvocationStarted(ToolUseId toolUseId) { }

    default void toolSideEffectObserved(
            ToolUseId toolUseId, ToolSideEffect sideEffect) { }

    default void toolInvocationSettled(ToolUseId toolUseId, ToolResult result) { }

    default void compactionCompleted(
            Conversation conversation, CompactionBoundary boundary) { }

    default void runSuspended(RunSuspension suspension) { }

    default void approvalSubmitted(
            RunSuspension.WaitingForApproval suspension,
            ApprovalDecision decision) { }

    default void inputAnswered(
            RunSuspension.WaitingForInput suspension,
            InputAnswer answer) { }

    default void runStopped(AgentRunResult result) { }

    final class PersistingRunEventRecorder implements RunEventRecorder {
        private final RunEventStore store;
        private final AgentRunContext context;
        private final AtomicLong sequence = new AtomicLong();

        private PersistingRunEventRecorder(
                RunEventStore store, AgentRunContext context) {
            this.store = Objects.requireNonNull(store, "store");
            this.context = Objects.requireNonNull(context, "context");
        }

        @Override
        public void runStarted(Conversation conversation) {
            append(new RunEvent.RunStarted(
                    metadata(), conversation.messages(), conversation.lastCompaction()));
        }

        @Override
        public void llmCallStarted(int messageCount) {
            append(new RunEvent.LlmCallStarted(metadata(), messageCount));
        }

        @Override
        public void assistantTurnReceived(AiMessage message) {
            append(new RunEvent.AssistantTurnReceived(metadata(), message));
        }

        @Override
        public void toolInvocationStarted(ToolUseId toolUseId) {
            append(new RunEvent.ToolInvocationStarted(metadata(), toolUseId));
        }

        @Override
        public void toolSideEffectObserved(
                ToolUseId toolUseId, ToolSideEffect sideEffect) {
            append(new RunEvent.ToolSideEffectObserved(
                    metadata(), toolUseId, sideEffect));
        }

        @Override
        public void toolInvocationSettled(ToolUseId toolUseId, ToolResult result) {
            append(new RunEvent.ToolInvocationSettled(metadata(), toolUseId, result));
        }

        @Override
        public void compactionCompleted(
                Conversation conversation, CompactionBoundary boundary) {
            List<ChatMessage> messages = conversation.messages();
            append(new RunEvent.CompactionCompleted(
                    metadata(), boundary, messages.subList(1, messages.size())));
        }

        @Override
        public void runSuspended(RunSuspension suspension) {
            append(new RunEvent.RunSuspended(metadata(), suspension));
        }

        @Override
        public void approvalSubmitted(
                RunSuspension.WaitingForApproval suspension,
                ApprovalDecision decision) {
            append(new RunEvent.ApprovalSubmitted(metadata(), suspension, decision));
        }

        @Override
        public void inputAnswered(
                RunSuspension.WaitingForInput suspension,
                InputAnswer answer) {
            append(new RunEvent.InputAnswered(metadata(), suspension, answer));
        }

        @Override
        public void runStopped(AgentRunResult result) {
            append(new RunEvent.RunStopped(
                    metadata(), result.stopReason(), result.finalMessage(),
                    result.structuredOutput(), result.usage(), result.consumption(),
                    result.errorDetail()));
        }

        private RunEventMetadata metadata() {
            return new RunEventMetadata(
                    RunEvent.CURRENT_SCHEMA_VERSION,
                    context.runId(), context.sessionId(), context.workspaceId(),
                    sequence.incrementAndGet(), Instant.now());
        }

        private void append(RunEvent event) {
            try {
                store.append(event);
            } catch (RunEventPersistenceException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new RunEventPersistenceException(
                        "failed to persist run event sequence "
                                + event.metadata().sequence(), failure);
            }
        }
    }
}
