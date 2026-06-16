package com.anthropic.agentkit.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PermissionDecisionCache {

    private final Set<String> allowedToolNames = ConcurrentHashMap.newKeySet();

    boolean allows(String toolName) {
        return allowedToolNames.contains(toolName);
    }

    void recordAllowAlways(String toolName) {
        allowedToolNames.add(toolName);
    }

    void clear() {
        allowedToolNames.clear();
    }
}
