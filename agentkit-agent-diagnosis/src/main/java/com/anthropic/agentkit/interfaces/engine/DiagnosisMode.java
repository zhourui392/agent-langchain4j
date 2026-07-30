package com.anthropic.agentkit.interfaces.engine;

/**
 * Declares whether an engine is knowledge-only or expected to gather real evidence.
 *
 * @author alex
 */
public enum DiagnosisMode {
    CONVERSATIONAL,
    OPERATIONAL
}
