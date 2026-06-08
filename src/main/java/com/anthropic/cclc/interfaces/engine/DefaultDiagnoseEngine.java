package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ToolRegistry;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Default {@link DiagnoseEngine}: rebuilds a stateless conversation, runs the
 * shared {@code AgentExecutor} under a read-only permission policy, and streams
 * events as Claude {@code stream-json}. Stub for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class DefaultDiagnoseEngine implements DiagnoseEngine {

    private final LlmClient llm;
    private final ToolRegistry tools;

    public DefaultDiagnoseEngine(LlmClient llm, ToolRegistry tools) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    @Override
    public void runStream(RunRequest request, Consumer<String> onChunk, IntConsumer onExit) {
        onExit.accept(0);
    }

    @Override
    public void stop(String sessionId) {
    }

    @Override
    public boolean isRunning(String sessionId) {
        return false;
    }
}
