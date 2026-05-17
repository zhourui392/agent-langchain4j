package com.anthropic.cclc.infrastructure.llm;

public record CacheBreakpointStrategy(boolean cacheSystemPrompt, boolean cacheToolDefinitions) {

    public static CacheBreakpointStrategy enabled() {
        return new CacheBreakpointStrategy(true, true);
    }

    public static CacheBreakpointStrategy disabled() {
        return new CacheBreakpointStrategy(false, false);
    }

    public static CacheBreakpointStrategy systemOnly() {
        return new CacheBreakpointStrategy(true, false);
    }
}
