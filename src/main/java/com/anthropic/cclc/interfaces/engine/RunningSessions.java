package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.domain.conversation.CancellationToken;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of in-flight runs keyed by host session id, so {@code stop} and
 * {@code isRunning} can reach a running turn's {@link CancellationToken}.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
final class RunningSessions {

    private final Map<String, CancellationToken> tokens = new ConcurrentHashMap<>();

    CancellationToken register(String sessionId) {
        CancellationToken token = new CancellationToken();
        tokens.put(sessionId, token);
        return token;
    }

    void cancel(String sessionId) {
        CancellationToken token = tokens.get(sessionId);
        if (token != null) {
            token.cancel();
        }
    }

    boolean isRunning(String sessionId) {
        return tokens.containsKey(sessionId);
    }

    void remove(String sessionId) {
        tokens.remove(sessionId);
    }
}
