package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps optional observer failures outside the agent's required execution facts. */
final class SafeAgentEventListener implements AgentEventListener {

    private static final Logger log = LoggerFactory.getLogger(SafeAgentEventListener.class);

    private final AgentEventListener delegate;
    private final Set<String> disabledCallbacks = ConcurrentHashMap.newKeySet();

    private SafeAgentEventListener(AgentEventListener delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    static AgentEventListener protect(AgentEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (listener == AgentEventListener.NO_OP || listener instanceof SafeAgentEventListener) {
            return listener;
        }
        return new SafeAgentEventListener(listener);
    }

    @Override
    public void onRunStart(AgentRunContext context) {
        invoke("onRunStart", () -> delegate.onRunStart(context));
    }

    @Override
    public void onLlmRequestStart() {
        invoke("onLlmRequestStart", delegate::onLlmRequestStart);
    }

    @Override
    public void onAssistantTextDelta(String delta) {
        invoke("onAssistantTextDelta", () -> delegate.onAssistantTextDelta(delta));
    }

    @Override
    public void onToolUseStart(ToolUseRequest request) {
        invoke("onToolUseStart", () -> delegate.onToolUseStart(request));
    }

    @Override
    public void onToolUseEnd(ToolUseRequest request, ToolResult result, long durationMs) {
        invoke("onToolUseEnd", () -> delegate.onToolUseEnd(request, result, durationMs));
    }

    @Override
    public void onTurnComplete(AiMessage finalMessage) {
        invoke("onTurnComplete", () -> delegate.onTurnComplete(finalMessage));
    }

    @Override
    public void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
        invoke("onUsage", () -> delegate.onUsage(
                inputTokens, outputTokens, cacheReadInputTokens));
    }

    @Override
    public void onError(Throwable error) {
        invoke("onError", () -> delegate.onError(error));
    }

    private void invoke(String callback, Runnable action) {
        if (disabledCallbacks.contains(callback)) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException failure) {
            if (disabledCallbacks.add(callback)) {
                log.warn("agent listener callback failed and was disabled: callback={}",
                        callback, failure);
            }
        }
    }
}
