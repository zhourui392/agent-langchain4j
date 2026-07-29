package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolInvocation;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PermissionDecisionCache {

    private final Map<RunId, Set<PermissionGrant>> grantsByRun = new ConcurrentHashMap<>();

    boolean allows(ExecutionContext context, ToolInvocation invocation) {
        return grantsByRun.getOrDefault(context.runId(), Set.of())
                .contains(PermissionGrant.from(context, invocation));
    }

    void recordAllowAlways(ExecutionContext context, ToolInvocation invocation) {
        grantsByRun.computeIfAbsent(
                        context.runId(), ignored -> ConcurrentHashMap.newKeySet())
                .add(PermissionGrant.from(context, invocation));
    }

    void clear(RunId runId) {
        grantsByRun.remove(runId);
    }

    private record PermissionGrant(
            RunId runId,
            WorkspaceId workspaceId,
            String toolName,
            Map<String, Object> arguments) {

        private static PermissionGrant from(
                ExecutionContext context, ToolInvocation invocation) {
            return new PermissionGrant(
                    context.runId(), context.workspaceId(), invocation.toolName(),
                    Map.copyOf(invocation.args().values()));
        }
    }
}
