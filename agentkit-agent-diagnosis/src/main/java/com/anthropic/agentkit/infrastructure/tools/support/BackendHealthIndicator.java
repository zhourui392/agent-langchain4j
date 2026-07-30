package com.anthropic.agentkit.infrastructure.tools.support;

/**
 * Backend adapter that can publish sanitized startup readiness.
 *
 * @author alex
 */
public interface BackendHealthIndicator {

    BackendHealth health();
}
