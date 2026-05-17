package com.anthropic.cclc.application;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.ToolResultMessage;
import com.anthropic.cclc.domain.permission.Decision;
import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolInvocation;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class ParallelToolDispatcher {

    private final ToolRegistry tools;
    private final ExecutionContext executionContext;
    private final PermissionService permissions;

    ParallelToolDispatcher(ToolRegistry tools, ExecutionContext executionContext, PermissionService permissions) {
        this.tools = tools;
        this.executionContext = executionContext;
        this.permissions = permissions;
    }

    List<ToolResultMessage> dispatch(AiMessage aiMessage) {
        return dispatch(aiMessage, AgentEventListener.NO_OP);
    }

    List<ToolResultMessage> dispatch(AiMessage aiMessage, AgentEventListener listener) {
        List<ToolUseRequest> requests = aiMessage.toolUseRequests();
        if (requests.size() == 1) {
            return List.of(executeSingle(requests.get(0), listener));
        }
        return executeAll(requests, listener);
    }

    private ToolResultMessage executeSingle(ToolUseRequest request, AgentEventListener listener) {
        ToolResult result = runWithEvents(request, listener);
        return ToolResultMessage.of(request.id(), result.content());
    }

    private List<ToolResultMessage> executeAll(List<ToolUseRequest> requests, AgentEventListener listener) {
        ConcurrentHashMap<ToolUseId, ToolResult> resultsById = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(requests.size());
            for (ToolUseRequest req : requests) {
                futures.add(pool.submit(() -> resultsById.put(req.id(), runWithEvents(req, listener))));
            }
            awaitAll(futures);
        }
        return assembleInOrder(requests, resultsById);
    }

    private ToolResult runWithEvents(ToolUseRequest request, AgentEventListener listener) {
        listener.onToolUseStart(request);
        long startNs = System.nanoTime();
        ToolResult result = runWithPermission(request);
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        listener.onToolUseEnd(request, result, durationMs);
        return result;
    }

    private ToolResult runWithPermission(ToolUseRequest request) {
        Tool tool = tools.find(request.toolName());
        ToolInvocation invocation = InvocationFactory.from(request);
        Decision decision = permissions.check(invocation, tool);
        if (decision == Decision.DENY) {
            invocation.deny();
            return ToolResult.error("permission denied: " + tool.name());
        }
        invocation.allow();
        return runTool(tool, invocation);
    }

    private ToolResult runTool(Tool tool, ToolInvocation invocation) {
        try {
            ToolResult result = tool.execute(invocation.args(), executionContext);
            invocation.complete(result);
            return result;
        } catch (RuntimeException ex) {
            ToolResult failure = ToolResult.error(ex.getMessage());
            invocation.fail(failure);
            return failure;
        }
    }

    private static void awaitAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while dispatching tools", ie);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(cause);
            }
        }
    }

    private static List<ToolResultMessage> assembleInOrder(
            List<ToolUseRequest> requests,
            ConcurrentHashMap<ToolUseId, ToolResult> resultsById) {
        List<ToolResultMessage> ordered = new ArrayList<>(requests.size());
        for (ToolUseRequest req : requests) {
            ToolResult result = resultsById.get(req.id());
            ordered.add(ToolResultMessage.of(req.id(), result.content()));
        }
        return ordered;
    }
}
