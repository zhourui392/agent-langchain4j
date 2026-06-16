package com.anthropic.agentkit.infrastructure.config;

import java.util.Arrays;

/**
 * Supported provider families for LangChain4j model construction.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public enum LlmProvider {
    ANTHROPIC,
    OPENAI;

    public static LlmProvider parse(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("invalid provider '" + raw
                    + "', expected one of: " + Arrays.toString(values()));
        }
    }
}
