package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.conversation.CompactionBoundary;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.port.RunEventPersistenceException;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.run.RunEventMetadata;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Required fact writer for one configured run; sequencing is independent from observers. */
final class RunEventRecorder {

    private final RunEventStore store;
    private final AgentRunContext context;
    private final AtomicLong sequence = new AtomicLong();
    private final boolean enabled;

    private RunEventRecorder(
            RunEventStore store, AgentRunContext context, boolean enabled) {
        this.store = Objects.requireNonNull(store, "store");
        this.context = context;
        this.enabled = enabled;
    }

    static RunEventRecorder forRun(RunEventStore store, AgentRunContext context) {
        return new RunEventRecorder(store, Objects.requireNonNull(context, "context"), true);
    }

    static RunEventRecorder disabled() {
        return new RunEventRecorder(RunEventStore.none(), null, false);
    }

    void runStarted(Conversation conversation) {
        if (!enabled) { return; }
        append(new RunEvent.RunStarted(
                metadata(), conversation.messages(), conversation.lastCompaction()));
    }

    void llmCallStarted(int messageCount) {
        if (!enabled) { return; }
        append(new RunEvent.LlmCallStarted(metadata(), messageCount));
    }

    void assistantTurnReceived(AiMessage message) {
        if (!enabled) { return; }
        append(new RunEvent.AssistantTurnReceived(metadata(), message));
    }

    void toolInvocationStarted(ToolUseId toolUseId) {
        if (!enabled) { return; }
        append(new RunEvent.ToolInvocationStarted(metadata(), toolUseId));
    }

    void toolInvocationSettled(ToolUseId toolUseId, ToolResult result) {
        if (!enabled) { return; }
        append(new RunEvent.ToolInvocationSettled(metadata(), toolUseId, result));
    }

    void compactionCompleted(Conversation conversation, CompactionBoundary boundary) {
        if (!enabled) { return; }
        List<com.anthropic.agentkit.domain.message.ChatMessage> messages = conversation.messages();
        append(new RunEvent.CompactionCompleted(
                metadata(), boundary, messages.subList(1, messages.size())));
    }

    void runStopped(AgentRunResult result) {
        if (!enabled) { return; }
        append(new RunEvent.RunStopped(
                metadata(), result.stopReason(), result.finalMessage(),
                result.structuredOutput(), result.usage(), result.consumption(),
                result.errorDetail()));
    }

    private RunEventMetadata metadata() {
        if (!enabled) {
            return null;
        }
        return new RunEventMetadata(
                RunEvent.CURRENT_SCHEMA_VERSION,
                context.runId(), context.sessionId(), context.workspaceId(),
                sequence.incrementAndGet(), Instant.now());
    }

    private void append(RunEvent event) {
        if (!enabled) {
            return;
        }
        try {
            store.append(event);
        } catch (RunEventPersistenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RunEventPersistenceException(
                    "failed to persist run event sequence " + event.metadata().sequence(), failure);
        }
    }
}
