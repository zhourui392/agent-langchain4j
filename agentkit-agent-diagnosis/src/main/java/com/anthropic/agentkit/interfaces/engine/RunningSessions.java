package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.conversation.CancellationToken;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of in-flight runs keyed by host session id, so {@code stop} and
 * {@code isRunning} can reach a running turn's {@link CancellationToken}.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
final class RunningSessions {

    private final Map<String, RunControl> runs = new ConcurrentHashMap<>();

    Optional<RunControl> register(String sessionId) {
        RunControl control = new RunControl();
        RunControl existing = runs.putIfAbsent(sessionId, control);
        return existing == null ? Optional.of(control) : Optional.empty();
    }

    void cancel(String sessionId) {
        RunControl control = runs.get(sessionId);
        if (control != null) {
            control.cancel();
        }
    }

    boolean isRunning(String sessionId) {
        return runs.containsKey(sessionId);
    }

    void cancelAll() {
        runs.values().forEach(RunControl::cancel);
    }

    int size() {
        return runs.size();
    }

    void remove(String sessionId, RunControl control) {
        runs.remove(sessionId, control);
    }

    static final class RunControl {

        private final CancellationToken token = new CancellationToken();

        CancellationToken token() {
            return token;
        }

        boolean isCancelled() {
            return token.isCancelled();
        }

        void cancel() {
            token.cancel();
        }

    }
}
