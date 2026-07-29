package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.agent.AgentEntryPoint;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process entry point for the host (agent-web). Signatures use only JDK types
 * plus engine-owned DTOs so the dependency surface stays clean. {@code runStream}
 * blocks until the turn ends — the host drives it on its own thread, exactly as
 * it does the external CLI subprocess it replaces.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public interface DiagnoseEngine extends AgentEntryPoint<RunRequest, RunSummary>, AutoCloseable {

    @Override
    default Class<RunRequest> requestType() {
        return RunRequest.class;
    }

    @Override
    default Class<RunSummary> resultType() {
        return RunSummary.class;
    }

    @Override
    default RunSummary invoke(RunRequest request) {
        AtomicReference<RunSummary> result = new AtomicReference<>();
        run(request, ignored -> { }, result::set);
        return Objects.requireNonNull(result.get(), "diagnosis run did not complete");
    }

    /**
     * Runs one diagnosis turn and reports the structured terminal state.
     *
     * @param request diagnosis request
     * @param onChunk receives one Claude stream-json line at a time
     * @param onComplete receives the terminal summary for persistence
     */
    void run(RunRequest request, Consumer<String> onChunk, Consumer<RunSummary> onComplete);

    /**
     * Runs one diagnosis turn, pushing each Claude {@code stream-json} line to
     * {@code onChunk}, then reports the exit code: {@code 0} success,
     * {@code -1} stopped/timed out, {@code 1} error.
     */
    @Deprecated
    default void runStream(RunRequest request, Consumer<String> onChunk, IntConsumer onExit) {
        Objects.requireNonNull(onExit, "onExit");
        run(request, onChunk, summary -> onExit.accept(summary.legacyExitCode()));
    }

    /** Cancels the in-flight run for the given session, if any. */
    void stop(String sessionId);

    boolean isRunning(String sessionId);

    /**
     * Rejects new runs, cancels in-flight runs, and waits briefly for drain.
     */
    @Override
    void close();
}
