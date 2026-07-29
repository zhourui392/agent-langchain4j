package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.application.AgentEventListener;
import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.InteractivePrompter;
import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.permission.ReadOnlyPermissionPolicy;

import java.util.Objects;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spawns an isolated read-only sub-agent for one delegated task, returning its
 * final response as the tool result. The child runs its own {@code AgentExecutor}
 * over a fresh {@code Conversation} with a narrowed tool set, inheriting only the
 * parent's run scope and LLM client.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class SubAgentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SubAgentTool.class);

    private static final InteractivePrompter REJECTING_PROMPTER = (invocation, tool) -> {
        throw new IllegalStateException("read-only sub-agent has no interactive approval");
    };

    private final LlmClient llm;
    private final ToolRegistry childTools;
    private final AgentBudget childBudget;
    private final AgentRunLimits childLimits;

    public SubAgentTool(LlmClient llm, ToolRegistry childTools) {
        this(llm, childTools, null, null);
    }

    public SubAgentTool(LlmClient llm, ToolRegistry childTools,
                        AgentBudget childBudget, AgentRunLimits childLimits) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.childTools = Objects.requireNonNull(childTools, "childTools");
        if ((childBudget == null) != (childLimits == null)) {
            throw new IllegalArgumentException(
                    "childBudget and childLimits must be configured together");
        }
        this.childBudget = childBudget;
        this.childLimits = childLimits;
    }

    @Override
    public String name() {
        return "Task";
    }

    @Override
    public String description() {
        return "Launch an isolated read-only sub-agent for one delegated task; "
                + "returns its final response.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}},"
                + "\"required\":[\"prompt\"]}";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        String prompt = args.getString("prompt");
        log.info("sub-agent started: promptChars={}, childToolCount={}", prompt.length(), childTools.names().size());
        Conversation child = new Conversation(SessionId.fresh());
        child.append(UserMessage.of(prompt));
        try {
            AgentRunResult result = childExecutor()
                    .run(child, childContext(ctx, child.sessionId()), AgentEventListener.NO_OP)
                    .join();
            if (result.stopReason() != StopReason.MODEL_COMPLETED
                    && result.stopReason() != StopReason.TERMINAL_TOOL) {
                return stoppedResult(result.stopReason());
            }
            AiMessage finalMessage = result.finalMessage();
            log.info("sub-agent completed: sessionId={}, turns={}, resultChars={}, durationMs={}",
                    child.sessionId(), assistantTurns(child), finalMessage.text().length(), elapsedMs(startNs));
            return ToolResult.ok(finalMessage.text());
        } catch (CompletionException ex) {
            log.warn("sub-agent failed: sessionId={}, cancelled={}, durationMs={}",
                    child.sessionId(), ctx.cancellation().isCancelled(), elapsedMs(startNs));
            return ToolResult.error(failureMessage(ctx, ex));
        }
    }

    private AgentExecutor childExecutor() {
        PermissionService permissions = new PermissionService(
                new ReadOnlyPermissionPolicy(), REJECTING_PROMPTER, PermissionMode.BYPASS);
        return new AgentExecutor(llm, childTools, permissions);
    }

    private AgentRunContext childContext(ExecutionContext parent, SessionId childSession) {
        if (childBudget == null || childLimits == null) {
            return AgentRunContext.childOf(parent, childSession);
        }
        return AgentRunContext.childOf(parent, childSession, childBudget, childLimits);
    }

    private static String failureMessage(ExecutionContext ctx, CompletionException ex) {
        if (ctx.cancellation().isCancelled()) {
            return "sub-agent cancelled";
        }
        Throwable cause = ex.getCause();
        return "sub-agent failed: " + (cause == null ? ex.getMessage() : cause.getMessage());
    }

    private static ToolResult stoppedResult(StopReason reason) {
        return switch (reason) {
            case CANCELLED -> ToolResult.of(
                    com.anthropic.agentkit.domain.tool.ToolResultStatus.CANCELLED,
                    "sub-agent cancelled");
            case TIMED_OUT -> ToolResult.of(
                    com.anthropic.agentkit.domain.tool.ToolResultStatus.TIMEOUT,
                    "sub-agent timed out");
            case BUDGET_EXHAUSTED -> ToolResult.of(
                    com.anthropic.agentkit.domain.tool.ToolResultStatus.BUDGET_EXHAUSTED,
                    "sub-agent budget exhausted");
            default -> ToolResult.error("sub-agent stopped: " + reason);
        };
    }

    private static long assistantTurns(Conversation conversation) {
        return conversation.messages().stream().filter(AiMessage.class::isInstance).count();
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
