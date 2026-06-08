package com.anthropic.cclc.interfaces.engine;

/**
 * Assembles single-line Claude {@code stream-json} events that agent-web's
 * consumer parses verbatim. See {@code docs/samples/README.md} for the binding
 * field contract. Pure function object, no side effects. Stub for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class ClaudeStreamJsonWriter {

    /** Token accounting echoed on the result event (agent-web persists it). */
    public record Usage(long inputTokens, long outputTokens, long cacheReadInputTokens) {
    }

    public String systemInit(String sessionId, String cwd) {
        return "{}";
    }

    public String textDelta(String text) {
        return "{}";
    }

    public String toolUseStart(String id, String name) {
        return "{}";
    }

    public String inputJsonDelta(String partialJson) {
        return "{}";
    }

    public String contentBlockStop() {
        return "{}";
    }

    public String toolResult(String toolUseId, String content) {
        return "{}";
    }

    public String result(String finalText, String sessionId) {
        return "{}";
    }

    public String result(String finalText, String sessionId, Usage usage) {
        return "{}";
    }

    public String errorResult(String message, String sessionId) {
        return "{}";
    }
}
