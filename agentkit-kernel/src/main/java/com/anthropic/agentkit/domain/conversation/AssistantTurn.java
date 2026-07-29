package com.anthropic.agentkit.domain.conversation;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Consistency boundary for one assistant message's ordered tool-use batch.
 */
public final class AssistantTurn {

    private final List<ToolUseId> toolUseIds;
    private int settledCount;
    private AssistantTurnState state = AssistantTurnState.RECEIVED;

    private AssistantTurn(List<ToolUseId> toolUseIds) {
        this.toolUseIds = List.copyOf(toolUseIds);
    }

    public static AssistantTurn from(AiMessage message) {
        Objects.requireNonNull(message, "message");
        if (!message.hasToolUseRequests()) {
            throw new IllegalArgumentException("assistant turn must contain a tool-use batch");
        }
        List<ToolUseId> ids = message.toolUseRequests().stream()
                .map(ToolUseRequest::id)
                .toList();
        validateUnique(ids);
        return new AssistantTurn(ids);
    }

    public List<ToolUseId> toolUseIds() {
        return toolUseIds;
    }

    public int settledCount() {
        return settledCount;
    }

    public AssistantTurnState state() {
        return state;
    }

    public boolean isSettled() {
        return state == AssistantTurnState.SETTLED;
    }

    public void settle(ToolResultMessage result) {
        Objects.requireNonNull(result, "result");
        if (isSettled()) {
            throw new IllegalStateException("assistant tool batch is already settled");
        }
        ToolUseId expected = toolUseIds.get(settledCount);
        if (!expected.equals(result.toolUseId())) {
            throw new IllegalArgumentException(
                    "tool result is out of original batch order: expected "
                            + expected + " but got " + result.toolUseId());
        }
        settledCount++;
        state = settledCount == toolUseIds.size()
                ? AssistantTurnState.SETTLED
                : AssistantTurnState.SETTLING;
    }

    private static void validateUnique(List<ToolUseId> ids) {
        Set<ToolUseId> unique = new HashSet<>();
        for (ToolUseId id : ids) {
            if (!unique.add(id)) {
                throw new IllegalArgumentException("duplicate tool use id: " + id);
            }
        }
    }
}
