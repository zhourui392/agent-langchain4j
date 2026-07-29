package com.anthropic.agentkit.application.context;

import com.anthropic.agentkit.domain.agent.AgentBudgetExceededException;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.ModelIdentity;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.CompactionBoundary;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.TokenBudget;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.Role;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.ContextWindowExceededException;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Threshold and reactive context compaction shared by every agent entry point. */
public final class ContextCompactionService implements ContextPolicy {

    private static final String SUMMARY_SYSTEM =
            "You compress conversation history for an engineering agent.";
    private static final String SUMMARY_INSTRUCTION =
            "Summarize the earlier conversation below. Preserve findings, decisions, open questions, "
                    + "and every tool name, tool-use id, arguments, result status, and relevant metadata:";

    private final LlmClient llm;
    private final TokenBudget budget;
    private final int recentMessages;

    public ContextCompactionService(LlmClient llm, TokenBudget budget, int recentMessages) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.budget = Objects.requireNonNull(budget, "budget");
        if (recentMessages <= 0) {
            throw new IllegalArgumentException("recentMessages must be positive");
        }
        this.recentMessages = recentMessages;
    }

    @Override
    public ContextDecision beforeLlmCall(
            Conversation conversation, AgentRunContext context) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(context, "context");
        if (!budget.thresholdReached(estimateTokens(conversation.messages()))) {
            return ContextDecision.unchanged();
        }
        return compact(conversation, context);
    }

    @Override
    public ContextDecision recoverFromOverflow(
            Conversation conversation, Throwable failure, AgentRunContext context) {
        if (!isContextOverflow(failure)) {
            return ContextDecision.notApplicableDecision();
        }
        return compact(conversation, context);
    }

    @Override
    public boolean isContextOverflow(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ContextWindowExceededException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ContextDecision compact(
            Conversation conversation, AgentRunContext context) {
        List<ChatMessage> messages = conversation.messages();
        int recentStart = pairingSafeRecentStart(messages);
        if (recentStart <= 0) {
            return ContextDecision.stopped(
                    StopReason.CONTEXT_EXHAUSTED, "no complete history segment can be compacted");
        }
        SummaryResult summary = summarize(messages.subList(0, recentStart), context);
        if (summary.stopReason().isPresent()) {
            return ContextDecision.stopped(
                    summary.stopReason().orElseThrow(), summary.errorDetail());
        }
        return install(conversation, messages, recentStart, summary.text().orElseThrow());
    }

    private ContextDecision install(
            Conversation conversation, List<ChatMessage> messages, int recentStart, String summary) {
        if (summary.isBlank()) {
            return ContextDecision.stopped(
                    StopReason.CONTEXT_EXHAUSTED, "context summarizer returned an empty summary");
        }
        int version = conversation.lastCompaction()
                .map(boundary -> boundary.summaryVersion() + 1).orElse(1);
        CompactionBoundary boundary = new CompactionBoundary(
                0, recentStart, estimateTokens(messages.subList(0, recentStart)),
                version, summary);
        conversation.installCompaction(
                boundary, messages.subList(recentStart, messages.size()));
        return ContextDecision.compactedContext();
    }

    private int pairingSafeRecentStart(List<ChatMessage> messages) {
        int start = Math.max(0, messages.size() - recentMessages);
        while (start < messages.size() && messages.get(start) instanceof ToolResultMessage) {
            start++;
        }
        return start;
    }

    private SummaryResult summarize(
            List<ChatMessage> older, AgentRunContext context) {
        ChatRequest request = summaryRequest(older);
        ModelIdentity model = llm.modelIdentity();
        context.budgetState().reserveLlmCall(context.budget(), model);
        CompactionStreamHandler handler = new CompactionStreamHandler(context, model);
        LlmCall call;
        try {
            call = llm.streamChat(request, handler);
        } catch (RuntimeException failure) {
            return SummaryResult.failed(
                    StopReason.CONTEXT_EXHAUSTED, messageOf(failure));
        }
        return awaitSummary(call, context);
    }

    private SummaryResult awaitSummary(LlmCall call, AgentRunContext context) {
        long waitNanos = context.limits().providerWait().toNanos();
        if (waitNanos <= 0) {
            call.cancel();
            return SummaryResult.failed(StopReason.TIMED_OUT, "context summarizer timed out");
        }
        try (CancellationToken.Registration ignored =
                     context.cancellation().onCancel(call::cancel)) {
            AiMessage message = call.completion().toCompletableFuture()
                    .get(waitNanos, TimeUnit.NANOSECONDS);
            if (message.hasToolUseRequests()) {
                return SummaryResult.failed(
                        StopReason.CONTEXT_EXHAUSTED, "context summarizer returned tool calls");
            }
            return SummaryResult.completed(message.text());
        } catch (TimeoutException ex) {
            call.cancel();
            return SummaryResult.failed(StopReason.TIMED_OUT, "context summarizer timed out");
        } catch (CancellationException ex) {
            return SummaryResult.failed(cancellationReason(context), "context summarizer cancelled");
        } catch (ExecutionException ex) {
            return summaryFailure(ex.getCause(), context);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            call.cancel();
            return SummaryResult.failed(StopReason.CANCELLED, "context summarizer interrupted");
        }
    }

    private SummaryResult summaryFailure(Throwable failure, AgentRunContext context) {
        if (failure instanceof AgentBudgetExceededException) {
            return SummaryResult.failed(StopReason.BUDGET_EXHAUSTED, messageOf(failure));
        }
        if (failure instanceof CancellationException) {
            return SummaryResult.failed(cancellationReason(context), messageOf(failure));
        }
        return SummaryResult.failed(StopReason.CONTEXT_EXHAUSTED, messageOf(failure));
    }

    private ChatRequest summaryRequest(List<ChatMessage> older) {
        String transcript = older.stream().map(this::render)
                .collect(Collectors.joining("\n"));
        return ChatRequest.builder()
                .systemPrompt(SUMMARY_SYSTEM)
                .message(UserMessage.of(SUMMARY_INSTRUCTION + "\n\n" + transcript))
                .build();
    }

    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += budget.estimate(render(message));
        }
        return total;
    }

    private String render(ChatMessage message) {
        if (message instanceof AiMessage ai && ai.hasToolUseRequests()) {
            return renderAssistant(ai);
        }
        if (message instanceof ToolResultMessage result) {
            return "ToolResult[id=" + result.toolUseId().value()
                    + ", status=" + result.status()
                    + ", metadata=" + result.metadata() + "]: " + result.text();
        }
        return label(message.role()) + ": " + message.text();
    }

    private String renderAssistant(AiMessage message) {
        String uses = message.toolUseRequests().stream()
                .map(this::renderToolUse).collect(Collectors.joining(", "));
        return "Assistant: " + message.text() + "\ntool_uses=[" + uses + "]";
    }

    private String renderToolUse(ToolUseRequest request) {
        return "id=" + request.id().value() + ", name=" + request.toolName()
                + ", arguments=" + request.argumentsJson();
    }

    private static String label(Role role) {
        return switch (role) {
            case USER -> "User";
            case AI -> "Assistant";
            case TOOL -> "ToolResult";
            case SYSTEM -> "System";
        };
    }

    private static StopReason cancellationReason(AgentRunContext context) {
        return context.limits().deadline().isExpired()
                ? StopReason.TIMED_OUT : StopReason.CANCELLED;
    }

    private static String messageOf(Throwable failure) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return failure == null ? "context summarizer failed" : failure.getClass().getSimpleName();
        }
        return failure.getMessage();
    }

    private record SummaryResult(
            Optional<String> text, Optional<StopReason> stopReason, String errorDetail) {

        private static SummaryResult completed(String text) {
            return new SummaryResult(Optional.of(text), Optional.empty(), "");
        }

        private static SummaryResult failed(StopReason reason, String detail) {
            return new SummaryResult(Optional.empty(), Optional.of(reason), detail);
        }
    }

    private static final class CompactionStreamHandler implements LlmClient.StreamHandler {
        private final AgentRunContext context;
        private final ModelIdentity model;

        private CompactionStreamHandler(
                AgentRunContext context, ModelIdentity model) {
            this.context = context;
            this.model = model;
        }

        @Override
        public void onPartialText(String delta) {
            context.cancellation().throwIfCancelled();
            context.budgetState().recordOutputCharacters(delta.length());
            context.budgetState().ensureWithin(context.budget());
        }

        @Override
        public void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
            context.budgetState().recordUsage(
                    model, inputTokens, outputTokens, cacheReadInputTokens);
            context.budgetState().ensureWithin(context.budget());
        }
    }
}
