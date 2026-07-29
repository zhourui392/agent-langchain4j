package com.anthropic.agentkit.domain.conversation;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

final class ToolUseInvariantChecker {

    private final Deque<ToolUseId> pending = new ArrayDeque<>();
    private final Set<ToolUseId> registered = new HashSet<>();
    private final Set<ToolUseId> settled = new HashSet<>();

    void onAppend(ChatMessage message) {
        if (message instanceof ToolResultMessage result) {
            validateAndSettle(result);
            return;
        }
        if (!pending.isEmpty()) {
            throw new IllegalStateException("cannot append message while tool batch is pending");
        }
        if (message instanceof AiMessage ai) {
            registerToolUseRequests(ai);
        }
    }

    private void validateAndSettle(ToolResultMessage result) {
        ToolUseId id = result.toolUseId();
        if (settled.contains(id)) {
            throw new IllegalArgumentException("tool use already settled: " + id);
        }
        if (!registered.contains(id)) {
            throw new IllegalArgumentException("no matching tool use for result: " + id);
        }
        ToolUseId expected = pending.peekFirst();
        if (!id.equals(expected)) {
            throw new IllegalArgumentException(
                    "tool result is out of original batch order: expected " + expected + " but got " + id);
        }
        pending.removeFirst();
        settled.add(id);
    }

    private void registerToolUseRequests(AiMessage ai) {
        Set<ToolUseId> batchIds = new HashSet<>();
        for (ToolUseRequest req : ai.toolUseRequests()) {
            ToolUseId id = req.id();
            if (!batchIds.add(id) || registered.contains(id)) {
                throw new IllegalArgumentException("duplicate tool use id: " + id);
            }
        }
        for (ToolUseRequest req : ai.toolUseRequests()) {
            registered.add(req.id());
            pending.addLast(req.id());
        }
    }
}
