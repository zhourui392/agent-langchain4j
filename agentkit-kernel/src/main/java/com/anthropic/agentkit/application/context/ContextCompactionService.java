package com.anthropic.agentkit.application.context;

import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.conversation.TokenBudget;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.Role;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmClient.StreamHandler;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Compacts an over-budget conversation by summarising older messages into a
 * single boundary message and keeping the recent tail. Returns a new
 * {@link Conversation}; the domain aggregate is never mutated, and
 * {@code AgentExecutor} is untouched (the engine calls this at run start).
 *
 * <p>The recent boundary is advanced past any leading {@code tool_result} so the
 * kept tail never starts with a result orphaned from its {@code tool_use} —
 * keeping {@code ToolUseInvariantChecker} satisfied.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class ContextCompactionService {

    private static final String SUMMARY_SYSTEM =
            "You compress conversation history for an engineering agent.";
    private static final String SUMMARY_INSTRUCTION =
            "Summarise the earlier conversation below, preserving findings, decisions, "
                    + "and open questions:";

    private final LlmClient llm;
    private final TokenBudget budget;
    private final int recentMessages;

    public ContextCompactionService(LlmClient llm, TokenBudget budget, int recentMessages) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.recentMessages = recentMessages;
    }

    public Conversation maybeCompact(Conversation conversation) {
        List<ChatMessage> messages = conversation.messages();
        if (!budget.thresholdReached(estimateTokens(messages))) {
            return conversation;
        }
        int recentStart = pairingSafeRecentStart(messages);
        if (recentStart <= 0) {
            return conversation;
        }
        String summary = summarize(messages.subList(0, recentStart));
        return rebuild(conversation.sessionId(), summary, messages.subList(recentStart, messages.size()));
    }

    private int pairingSafeRecentStart(List<ChatMessage> messages) {
        int start = Math.max(0, messages.size() - recentMessages);
        while (start < messages.size() && messages.get(start) instanceof ToolResultMessage) {
            start++;
        }
        return start;
    }

    private Conversation rebuild(SessionId sessionId, String summary, List<ChatMessage> recent) {
        Conversation compacted = new Conversation(sessionId);
        compacted.append(UserMessage.of("[Earlier conversation summary]\n" + summary));
        for (ChatMessage message : recent) {
            compacted.append(message);
        }
        return compacted;
    }

    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += budget.estimate(message.text());
        }
        return total;
    }

    private String summarize(List<ChatMessage> older) {
        String transcript = older.stream().map(this::render).collect(Collectors.joining("\n"));
        ChatRequest request = ChatRequest.builder()
                .systemPrompt(SUMMARY_SYSTEM)
                .message(UserMessage.of(SUMMARY_INSTRUCTION + "\n\n" + transcript))
                .build();
        return llm.streamChat(request, new StreamHandler() {
            @Override
            public void onPartialText(String delta) {
            }
        }).completion().toCompletableFuture().join().text();
    }

    private String render(ChatMessage message) {
        return label(message.role()) + ": " + message.text();
    }

    private static String label(Role role) {
        return switch (role) {
            case USER -> "User";
            case AI -> "Assistant";
            case TOOL -> "ToolResult";
            case SYSTEM -> "System";
        };
    }
}
