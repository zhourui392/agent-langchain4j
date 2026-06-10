package com.anthropic.cclc.infrastructure.streamjson;

import java.util.Map;

/**
 * Emits specialized agent events through the kernel stream-json channel.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public interface ExtensionEventEmitter {

    void emit(String type, Map<String, Object> payload);
}
