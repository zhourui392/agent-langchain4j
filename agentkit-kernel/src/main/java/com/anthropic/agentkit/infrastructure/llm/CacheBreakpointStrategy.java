package com.anthropic.agentkit.infrastructure.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record CacheBreakpointStrategy(boolean cacheSystemPrompt, boolean cacheToolDefinitions) {

    private static final Logger log = LoggerFactory.getLogger(CacheBreakpointStrategy.class);

    public CacheBreakpointStrategy {
        log.debug("cache breakpoint strategy configured: systemPrompt={}, toolDefinitions={}",
                cacheSystemPrompt, cacheToolDefinitions);
    }

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
