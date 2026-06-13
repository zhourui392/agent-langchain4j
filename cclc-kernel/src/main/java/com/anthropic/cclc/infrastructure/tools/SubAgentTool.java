package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.application.AgentEventListener;
import com.anthropic.cclc.application.AgentExecutor;
import com.anthropic.cclc.application.InteractivePrompter;
import com.anthropic.cclc.application.PermissionService;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.permission.ReadOnlyPermissionPolicy;

import java.util.Objects;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spawns an isolated read-only sub-agent to chase one hypothesis, returning its
 * final findings as the tool result. The child runs its own {@code AgentExecutor}
 * over a fresh {@code Conversation} with a narrowed tool set, inheriting only the
 * parent's cwd, cancellation, and LLM client.
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

    public SubAgentTool(LlmClient llm, ToolRegistry childTools) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.childTools = Objects.requireNonNull(childTools, "childTools");
    }

    @Override
    public String name() {
        return "Task";
    }

    @Override
    public String description() {
        return "Launch an isolated read-only sub-agent to investigate one hypothesis; "
                + "returns its final findings.";
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
            AiMessage finalMessage = childExecutor(ctx)
                    .run(child, ctx.cancellation(), AgentEventListener.NO_OP)
                    .join();
            log.info("sub-agent completed: sessionId={}, turns={}, resultChars={}, durationMs={}",
                    child.sessionId(), assistantTurns(child), finalMessage.text().length(), elapsedMs(startNs));
            return ToolResult.ok(finalMessage.text());
        } catch (CompletionException ex) {
            log.warn("sub-agent failed: sessionId={}, cancelled={}, durationMs={}",
                    child.sessionId(), ctx.cancellation().isCancelled(), elapsedMs(startNs));
            return ToolResult.error(failureMessage(ctx, ex));
        }
    }

    private AgentExecutor childExecutor(ExecutionContext ctx) {
        PermissionService permissions = new PermissionService(
                new ReadOnlyPermissionPolicy(), REJECTING_PROMPTER, PermissionMode.BYPASS);
        return new AgentExecutor(llm, childTools, permissions, ctx);
    }

    private static String failureMessage(ExecutionContext ctx, CompletionException ex) {
        if (ctx.cancellation().isCancelled()) {
            return "sub-agent cancelled";
        }
        Throwable cause = ex.getCause();
        return "sub-agent failed: " + (cause == null ? ex.getMessage() : cause.getMessage());
    }

    private static long assistantTurns(Conversation conversation) {
        return conversation.messages().stream().filter(AiMessage.class::isInstance).count();
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
