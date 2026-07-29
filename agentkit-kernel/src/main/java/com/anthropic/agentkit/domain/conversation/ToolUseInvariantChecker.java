package com.anthropic.agentkit.domain.conversation;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;

import java.util.HashSet;
import java.util.Set;

final class ToolUseInvariantChecker {

    private final Set<ToolUseId> registered = new HashSet<>();
    private final Set<ToolUseId> settled = new HashSet<>();
    private AssistantTurn activeTurn;

    void onAppend(ChatMessage message) {
        if (message instanceof ToolResultMessage result) {
            validateAndSettle(result);
            return;
        }
        if (activeTurn != null) {
            throw new IllegalStateException("cannot append message while tool batch is pending");
        }
        if (message instanceof AiMessage ai && ai.hasToolUseRequests()) {
            registerTurn(ai);
        }
    }

    private void validateAndSettle(ToolResultMessage result) {
        ToolUseId id = result.toolUseId();
        if (settled.contains(id)) {
            throw new IllegalArgumentException("tool use already settled: " + id);
        }
        if (!registered.contains(id) || activeTurn == null) {
            throw new IllegalArgumentException("no matching tool use for result: " + id);
        }
        activeTurn.settle(result);
        settled.add(id);
        if (activeTurn.isSettled()) {
            activeTurn = null;
        }
    }

    private void registerTurn(AiMessage message) {
        AssistantTurn candidate = AssistantTurn.from(message);
        for (ToolUseId id : candidate.toolUseIds()) {
            if (registered.contains(id)) {
                throw new IllegalArgumentException("duplicate tool use id: " + id);
            }
        }
        registered.addAll(candidate.toolUseIds());
        activeTurn = candidate;
    }
}
