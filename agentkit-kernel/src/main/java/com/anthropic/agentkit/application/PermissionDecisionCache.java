package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.RunId;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PermissionDecisionCache {

    private final Map<RunId, Set<String>> allowedToolNamesByRun = new ConcurrentHashMap<>();

    boolean allows(RunId runId, String toolName) {
        return allowedToolNamesByRun.getOrDefault(runId, Set.of()).contains(toolName);
    }

    void recordAllowAlways(RunId runId, String toolName) {
        allowedToolNamesByRun.computeIfAbsent(runId, ignored -> ConcurrentHashMap.newKeySet())
                .add(toolName);
    }

    void clear(RunId runId) {
        allowedToolNamesByRun.remove(runId);
    }
}
