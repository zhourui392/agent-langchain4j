package com.anthropic.agentkit.domain.run;

import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentUsage;
import com.anthropic.agentkit.domain.agent.BudgetConsumption;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.CompactionBoundary;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Append-only facts emitted during one agent run. */
public sealed interface RunEvent permits
        RunEvent.RunStarted,
        RunEvent.LlmCallStarted,
        RunEvent.AssistantTurnReceived,
        RunEvent.ToolInvocationStarted,
        RunEvent.ToolInvocationSettled,
        RunEvent.CompactionCompleted,
        RunEvent.RunStopped {

    int CURRENT_SCHEMA_VERSION = 1;

    RunEventMetadata metadata();

    record RunStarted(
            RunEventMetadata metadata,
            List<ChatMessage> initialMessages,
            Optional<CompactionBoundary> initialCompaction) implements RunEvent {
        public RunStarted {
            requireMetadata(metadata);
            initialMessages = List.copyOf(Objects.requireNonNull(initialMessages, "initialMessages"));
            initialCompaction = Objects.requireNonNull(initialCompaction, "initialCompaction");
        }
    }

    record LlmCallStarted(
            RunEventMetadata metadata,
            int messageCount) implements RunEvent {
        public LlmCallStarted {
            requireMetadata(metadata);
            if (messageCount < 0) {
                throw new IllegalArgumentException("messageCount must not be negative");
            }
        }
    }

    record AssistantTurnReceived(
            RunEventMetadata metadata,
            AiMessage message) implements RunEvent {
        public AssistantTurnReceived {
            requireMetadata(metadata);
            Objects.requireNonNull(message, "message");
        }
    }

    record ToolInvocationStarted(
            RunEventMetadata metadata,
            ToolUseId toolUseId) implements RunEvent {
        public ToolInvocationStarted {
            requireMetadata(metadata);
            Objects.requireNonNull(toolUseId, "toolUseId");
        }
    }

    record ToolInvocationSettled(
            RunEventMetadata metadata,
            ToolUseId toolUseId,
            ToolResult result) implements RunEvent {
        public ToolInvocationSettled {
            requireMetadata(metadata);
            Objects.requireNonNull(toolUseId, "toolUseId");
            Objects.requireNonNull(result, "result");
        }
    }

    record CompactionCompleted(
            RunEventMetadata metadata,
            CompactionBoundary boundary,
            List<ChatMessage> retainedMessages) implements RunEvent {
        public CompactionCompleted {
            requireMetadata(metadata);
            Objects.requireNonNull(boundary, "boundary");
            retainedMessages = List.copyOf(
                    Objects.requireNonNull(retainedMessages, "retainedMessages"));
        }
    }

    record RunStopped(
            RunEventMetadata metadata,
            StopReason stopReason,
            AiMessage finalMessage,
            Optional<Map<String, Object>> structuredOutput,
            AgentUsage usage,
            BudgetConsumption consumption,
            Optional<String> errorDetail) implements RunEvent {
        public RunStopped {
            requireMetadata(metadata);
            Objects.requireNonNull(stopReason, "stopReason");
            Objects.requireNonNull(finalMessage, "finalMessage");
            structuredOutput = Objects.requireNonNull(structuredOutput, "structuredOutput")
                    .map(Map::copyOf);
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(consumption, "consumption");
            errorDetail = Objects.requireNonNull(errorDetail, "errorDetail")
                    .filter(detail -> !detail.isBlank());
        }

        public AgentRunResult toResult() {
            return new AgentRunResult(
                    metadata.runId(), stopReason, finalMessage, structuredOutput,
                    usage, consumption, errorDetail);
        }
    }

    private static void requireMetadata(RunEventMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.schemaVersion() != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported run event schema version: " + metadata.schemaVersion());
        }
    }
}
