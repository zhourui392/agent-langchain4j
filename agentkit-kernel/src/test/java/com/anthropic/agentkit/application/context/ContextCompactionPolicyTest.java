package com.anthropic.agentkit.application.context;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.RunDeadline;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.conversation.TokenBudget;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ContextCompactionPolicyTest {

    @Test
    void compactionNeverSplitsToolBatch() {
        CapturingSummarizer llm = CapturingSummarizer.returning("summary");
        Conversation conversation = conversationWithToolBatch();
        ContextCompactionService policy = policy(llm, 2);

        ContextDecision decision = policy.beforeLlmCall(conversation, context(conversation));

        assertThat(decision.compacted()).isTrue();
        assertThat(conversation.messages()).noneMatch(ToolResultMessage.class::isInstance);
        assertThat(conversation.messages()).noneMatch(message ->
                message instanceof AiMessage ai && ai.hasToolUseRequests());
        assertThat(conversation.messages().getLast().text()).isEqualTo("recent question");
    }

    @Test
    void compactionPreservesToolNameArgumentsAndResultStatus() {
        CapturingSummarizer llm = CapturingSummarizer.returning("summary");
        Conversation conversation = conversationWithToolBatch();

        policy(llm, 1).beforeLlmCall(conversation, context(conversation));

        String transcript = llm.requests.getFirst().messages().getFirst().text();
        assertThat(transcript).contains("Grep", "{\"pattern\":\"secret\"}", "ERROR");
        assertThat(conversation.lastCompaction().orElseThrow().summaryVersion()).isEqualTo(1);
    }

    @Test
    void summarizerFailurePreservesOriginalConversation() {
        CapturingSummarizer llm = CapturingSummarizer.failing("summary unavailable");
        Conversation conversation = conversationWithToolBatch();
        List<ChatMessage> original = List.copyOf(conversation.messages());

        ContextDecision decision = policy(llm, 1)
                .beforeLlmCall(conversation, context(conversation));

        assertThat(decision.stopReason()).contains(StopReason.CONTEXT_EXHAUSTED);
        assertThat(conversation.messages()).containsExactlyElementsOf(original);
        assertThat(conversation.lastCompaction()).isEmpty();
    }

    @Test
    void emptySummaryCannotReplaceHistory() {
        CapturingSummarizer llm = CapturingSummarizer.returning("   ");
        Conversation conversation = conversationWithToolBatch();
        List<ChatMessage> original = List.copyOf(conversation.messages());

        ContextDecision decision = policy(llm, 1)
                .beforeLlmCall(conversation, context(conversation));

        assertThat(decision.stopReason()).contains(StopReason.CONTEXT_EXHAUSTED);
        assertThat(conversation.messages()).containsExactlyElementsOf(original);
        assertThat(conversation.lastCompaction()).isEmpty();
    }

    @Test
    void compactionLlmUsesRunDeadlineAndSharedUsageLedger() {
        BoundedSummarizer llm = new BoundedSummarizer();
        Conversation conversation = conversationWithToolBatch();
        AgentRunContext context = context(conversation).withLimits(new AgentRunLimits(
                RunDeadline.after(Duration.ofMillis(30)),
                Duration.ofSeconds(1), Duration.ofSeconds(1)));

        ContextDecision decision = policy(llm, 1).beforeLlmCall(conversation, context);

        assertThat(decision.stopReason()).contains(StopReason.TIMED_OUT);
        assertThat(llm.cancelled).isTrue();
        assertThat(context.budgetConsumption().inputTokens()).isEqualTo(7);
        assertThat(context.budgetConsumption().outputTokens()).isEqualTo(3);
        assertThat(conversation.lastCompaction()).isEmpty();
    }

    private static ContextCompactionService policy(LlmClient llm, int recent) {
        return new ContextCompactionService(llm, TokenBudget.of(40), recent);
    }

    private static AgentRunContext context(Conversation conversation) {
        return AgentRunContext.create(
                conversation.sessionId(), Path.of("."),
                new com.anthropic.agentkit.domain.conversation.CancellationToken(),
                com.anthropic.agentkit.domain.agent.AgentBudget.unlimited());
    }

    private static Conversation conversationWithToolBatch() {
        Conversation conversation = new Conversation(SessionId.of("compact-batch"));
        conversation.append(UserMessage.of("old context " + "x".repeat(200)));
        ToolUseId first = new ToolUseId("grep-1");
        ToolUseId second = new ToolUseId("read-1");
        conversation.append(AiMessage.of("checking", List.of(
                new ToolUseRequest(first, "Grep", "{\"pattern\":\"secret\"}"),
                new ToolUseRequest(second, "Read", "{\"path\":\"a.txt\"}"))));
        conversation.append(ToolResultMessage.of(
                first, ToolResultStatus.ERROR, "grep failed", java.util.Map.of("stage", "execute")));
        conversation.append(ToolResultMessage.of(second, "file body"));
        conversation.append(UserMessage.of("recent question"));
        return conversation;
    }

    private static final class CapturingSummarizer implements LlmClient {
        private final String summary;
        private final String failure;
        private final List<ChatRequest> requests = new ArrayList<>();

        private CapturingSummarizer(String summary, String failure) {
            this.summary = summary;
            this.failure = failure;
        }

        private static CapturingSummarizer returning(String summary) {
            return new CapturingSummarizer(summary, null);
        }

        private static CapturingSummarizer failing(String failure) {
            return new CapturingSummarizer(null, failure);
        }

        @Override
        public LlmCall streamChat(ChatRequest request, StreamHandler handler) {
            requests.add(request);
            return LlmCall.start(handler, sink -> {
                if (failure != null) {
                    sink.onError(new IllegalStateException(failure));
                } else {
                    sink.onComplete(AiMessage.text(summary));
                }
            });
        }
    }

    private static final class BoundedSummarizer implements LlmClient {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public LlmCall streamChat(ChatRequest request, StreamHandler handler) {
            return LlmCall.start(handler,
                    sink -> sink.onUsage(7, 3, 2),
                    () -> cancelled.set(true));
        }
    }
}
