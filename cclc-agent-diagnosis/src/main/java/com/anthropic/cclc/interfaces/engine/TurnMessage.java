package com.anthropic.cclc.interfaces.engine;

/**
 * One prior turn supplied by the host (agent-web) so the stateless engine can
 * rebuild a {@code Conversation}. Sealed to the three shapes the rebuilder
 * understands, enabling exhaustive pattern matching with no default branch.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public sealed interface TurnMessage permits UserTurn, AssistantTurn, ToolResultTurn {
}
