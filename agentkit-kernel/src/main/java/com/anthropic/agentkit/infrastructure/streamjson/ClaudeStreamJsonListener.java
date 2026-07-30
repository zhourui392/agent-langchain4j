package com.anthropic.agentkit.infrastructure.streamjson;

import com.anthropic.agentkit.application.AgentEventListener;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
public final class ClaudeStreamJsonListener implements AgentEventListener, ExtensionEventEmitter {

    private final String sessionId;
    private final String cwd;
    private final Consumer<String> onChunk;
    private final ClaudeStreamJsonWriter writer = new ClaudeStreamJsonWriter();
    private boolean initEmitted;
    private boolean usageReported;
    private int inputTokens;
    private int outputTokens;
    private int cacheReadInputTokens;
    private final ContentBlockBuffer contentBlocks = new ContentBlockBuffer();

    public ClaudeStreamJsonListener(String sessionId, String cwd, Consumer<String> onChunk) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.cwd = Objects.requireNonNull(cwd, "cwd");
        this.onChunk = Objects.requireNonNull(onChunk, "onChunk");
    }

    @Override
    public synchronized void onLlmRequestStart() {
        ensureInit();
    }

    @Override
    public synchronized void onAssistantTextDelta(String delta) {
        ensureInit();
        contentBlocks.appendText(delta);
        onChunk.accept(writer.textDelta(delta));
    }

    @Override
    public synchronized void onToolUseStart(ToolUseRequest request) {
        ensureInit();
        contentBlocks.appendToolUse(request);
        onChunk.accept(writer.toolUseStart(request.id().value(), request.toolName()));
        onChunk.accept(writer.inputJsonDelta(request.id().value(), request.argumentsJson()));
        onChunk.accept(writer.contentBlockStop());
    }

    @Override
    public synchronized void onToolUseEnd(ToolUseRequest request, ToolResult result, long durationMs) {
        ensureInit();
        flushAssistantMessage();
        onChunk.accept(writer.toolResult(request.id().value(), result.content()));
    }

    @Override
    public synchronized void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
        this.usageReported = true;
        this.inputTokens += inputTokens;
        this.outputTokens += outputTokens;
        this.cacheReadInputTokens += cacheReadInputTokens;
    }

    @Override
    public synchronized void onTurnComplete(AiMessage finalMessage) {
        ensureInit();
        flushAssistantMessage(finalMessage.text());
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
    public synchronized void onError(Throwable error) {
        ensureInit();
        onChunk.accept(writer.errorResult(String.valueOf(error.getMessage()), sessionId));
    }

    @Override
    public synchronized void emit(String type, Map<String, Object> payload) {
        ensureInit();
        onChunk.accept(writer.extensionEvent(type, payload));
    }

    private void ensureInit() {
        if (!initEmitted) {
            initEmitted = true;
            onChunk.accept(writer.systemInit(sessionId, cwd));
        }
    }

    private void flushAssistantMessage() {
        flushAssistantMessage(contentBlocks.text());
    }

    private void flushAssistantMessage(String text) {
        if (!contentBlocks.hasContent()) {
            return;
        }
        onChunk.accept(writer.assistantMessage(text, contentBlocks.toolUses()));
        contentBlocks.clear();
    }

    private static final class ContentBlockBuffer {

        private final StringBuilder text = new StringBuilder();
        private final List<ClaudeStreamJsonWriter.AssistantToolUse> toolUses = new ArrayList<>();

        private void appendText(String delta) {
            text.append(delta);
        }

        private void appendToolUse(ToolUseRequest request) {
            toolUses.add(new ClaudeStreamJsonWriter.AssistantToolUse(
                    request.id().value(), request.toolName(), request.argumentsJson()));
        }

        private String text() {
            return text.toString();
        }

        private List<ClaudeStreamJsonWriter.AssistantToolUse> toolUses() {
            return List.copyOf(toolUses);
        }

        private boolean hasContent() {
            return !text.isEmpty() || !toolUses.isEmpty();
        }

        private void clear() {
            text.setLength(0);
            toolUses.clear();
        }
    }
}
