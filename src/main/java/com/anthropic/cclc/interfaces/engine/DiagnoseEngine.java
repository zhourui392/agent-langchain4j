package com.anthropic.cclc.interfaces.engine;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * In-process entry point for the host (agent-web). Signatures use only JDK types
 * plus engine-owned DTOs so the dependency surface stays clean. {@code runStream}
 * blocks until the turn ends — the host drives it on its own thread, exactly as
 * it does the external CLI subprocess it replaces.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public interface DiagnoseEngine {

    /**
     * Runs one diagnosis turn, pushing each Claude {@code stream-json} line to
     * {@code onChunk}, then reports the exit code: {@code 0} success,
     * {@code -1} stopped/timed out, {@code 1} error.
     */
    void runStream(RunRequest request, Consumer<String> onChunk, IntConsumer onExit);

    /** Cancels the in-flight run for the given session, if any. */
    void stop(String sessionId);

    boolean isRunning(String sessionId);
}
