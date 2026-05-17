package com.anthropic.cclc.domain.conversation;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.ChatMessage;
import com.anthropic.cclc.domain.message.ToolResultMessage;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;

import java.util.HashSet;
import java.util.Set;

final class ToolUseInvariantChecker {

    private final Set<ToolUseId> pending = new HashSet<>();
    private final Set<ToolUseId> settled = new HashSet<>();

    void onAppend(ChatMessage message) {
        if (message instanceof ToolResultMessage result) {
            validateAndSettle(result);
        } else if (message instanceof AiMessage ai) {
            registerToolUseRequests(ai);
        }
    }

    private void validateAndSettle(ToolResultMessage result) {
        ToolUseId id = result.toolUseId();
        if (settled.contains(id)) {
            throw new IllegalArgumentException("tool use already settled: " + id);
        }
        if (!pending.contains(id)) {
            throw new IllegalArgumentException("no matching tool use for result: " + id);
        }
        pending.remove(id);
        settled.add(id);
    }

    private void registerToolUseRequests(AiMessage ai) {
        for (ToolUseRequest req : ai.toolUseRequests()) {
            pending.add(req.id());
        }
    }
}
