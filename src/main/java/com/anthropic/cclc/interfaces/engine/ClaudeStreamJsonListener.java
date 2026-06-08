package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.application.AgentEventListener;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.domain.tool.ToolUseRequest;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bridges {@link AgentEventListener} hooks to Claude {@code stream-json} lines,
 * pushed one at a time to {@code onChunk}. This is the only new wiring needed to
 * feed agent-web — {@code AgentExecutor} is untouched.
 *
 * <p>The {@code system.init} line is emitted lazily exactly once before the
 * first event, so the host can pick up {@code session_id} up front regardless of
 * which hook fires first.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class ClaudeStreamJsonListener implements AgentEventListener {

    private final String sessionId;
    private final String cwd;
    private final Consumer<String> onChunk;
    private final ClaudeStreamJsonWriter writer = new ClaudeStreamJsonWriter();
    private boolean initEmitted;
    private boolean usageReported;
    private int inputTokens;
    private int outputTokens;
    private int cacheReadInputTokens;

    public ClaudeStreamJsonListener(String sessionId, String cwd, Consumer<String> onChunk) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.cwd = Objects.requireNonNull(cwd, "cwd");
        this.onChunk = Objects.requireNonNull(onChunk, "onChunk");
    }

    @Override
    public void onLlmRequestStart() {
        ensureInit();
    }

    @Override
    public void onAssistantTextDelta(String delta) {
        ensureInit();
        onChunk.accept(writer.textDelta(delta));
    }

    @Override
    public void onToolUseStart(ToolUseRequest request) {
        ensureInit();
        onChunk.accept(writer.toolUseStart(request.id().value(), request.toolName()));
        onChunk.accept(writer.inputJsonDelta(request.argumentsJson()));
        onChunk.accept(writer.contentBlockStop());
    }

    @Override
    public void onToolUseEnd(ToolUseRequest request, ToolResult result, long durationMs) {
        ensureInit();
        onChunk.accept(writer.toolResult(request.id().value(), result.content()));
    }

    @Override
    public void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
        this.usageReported = true;
        this.inputTokens += inputTokens;
        this.outputTokens += outputTokens;
        this.cacheReadInputTokens += cacheReadInputTokens;
    }

    @Override
    public void onTurnComplete(AiMessage finalMessage) {
        ensureInit();
        onChunk.accept(resultLine(finalMessage.text()));
    }

    private String resultLine(String text) {
        if (!usageReported) {
            return writer.result(text, sessionId);
        }
        return writer.result(text, sessionId,
                new ClaudeStreamJsonWriter.Usage(inputTokens, outputTokens, cacheReadInputTokens));
    }

    @Override
    public void onError(Throwable error) {
        ensureInit();
        onChunk.accept(writer.errorResult(String.valueOf(error.getMessage()), sessionId));
    }

    private void ensureInit() {
        if (!initEmitted) {
            initEmitted = true;
            onChunk.accept(writer.systemInit(sessionId, cwd));
        }
    }
}
