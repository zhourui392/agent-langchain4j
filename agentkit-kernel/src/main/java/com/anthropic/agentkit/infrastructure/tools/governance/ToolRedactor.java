package com.anthropic.agentkit.infrastructure.tools.governance;

/**
 * Redacts sensitive text before it leaves a governed tool.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@FunctionalInterface
public interface ToolRedactor {

    ToolRedactor NO_OP = content -> content;

    String redact(String content);
}
