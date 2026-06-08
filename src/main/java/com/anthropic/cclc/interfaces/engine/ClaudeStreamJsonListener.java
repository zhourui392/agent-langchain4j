package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.application.AgentEventListener;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bridges {@link AgentEventListener} hooks to Claude {@code stream-json} lines,
 * pushed one at a time to {@code onChunk}. This is the only new wiring needed to
 * feed agent-web — {@code AgentExecutor} is untouched. Stub for Red.
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

    public ClaudeStreamJsonListener(String sessionId, String cwd, Consumer<String> onChunk) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.cwd = Objects.requireNonNull(cwd, "cwd");
        this.onChunk = Objects.requireNonNull(onChunk, "onChunk");
    }
}
