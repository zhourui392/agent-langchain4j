package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.ChatMessage;
import com.anthropic.cclc.domain.message.ToolResultMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;

import java.util.List;
import java.util.Objects;

/**
 * Rebuilds a {@link Conversation} from host-supplied history plus the current
 * user message. The engine is stateless: agent-web owns the session, so every
 * run reconstructs the conversation from scratch. Pairing of tool calls and
 * their results is preserved so {@code ToolUseInvariantChecker} stays happy.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class ConversationRebuilder {

    public Conversation from(SessionId sessionId, List<TurnMessage> history, String userMessage) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(userMessage, "userMessage");
        Conversation conversation = new Conversation(sessionId);
        for (TurnMessage turn : history) {
            conversation.append(toChatMessage(turn));
        }
        conversation.append(UserMessage.of(userMessage));
        return conversation;
    }

    private ChatMessage toChatMessage(TurnMessage turn) {
        return switch (turn) {
            case UserTurn user -> UserMessage.of(user.text());
            case AssistantTurn assistant -> toAiMessage(assistant);
            case ToolResultTurn result -> ToolResultMessage.of(
                    new ToolUseId(result.toolUseId()), result.content());
        };
    }

    private AiMessage toAiMessage(AssistantTurn assistant) {
        List<ToolUseRequest> requests = assistant.toolCalls().stream()
                .map(call -> new ToolUseRequest(new ToolUseId(call.id()), call.name(), call.argumentsJson()))
                .toList();
        return AiMessage.of(assistant.text(), requests);
    }
}
