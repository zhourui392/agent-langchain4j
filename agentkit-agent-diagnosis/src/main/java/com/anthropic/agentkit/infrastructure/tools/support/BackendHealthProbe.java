package com.anthropic.agentkit.infrastructure.tools.support;

/**
 * Host-owned light-weight read-only backend health operation.
 *
 * @author alex
 */
@FunctionalInterface
public interface BackendHealthProbe {

    BackendHealth probe();
}
