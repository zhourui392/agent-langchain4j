package com.anthropic.agentkit.interfaces.engine;

/**
 * Host-selected startup behavior for an unavailable operational engine.
 *
 * @author alex
 */
public record ReadinessPolicy(boolean failOnUnavailable) {

    public static ReadinessPolicy failFast() {
        return new ReadinessPolicy(true);
    }

    public static ReadinessPolicy degradedStartup() {
        return new ReadinessPolicy(false);
    }
}
