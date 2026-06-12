package com.anthropic.cclc.interfaces.engine;

import java.util.Objects;

/**
 * A single tool invocation an assistant turn requested, carried in history so
 * its result can be paired back on rebuild.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public record ToolCall(String id, String name, String argumentsJson) {
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }
}
