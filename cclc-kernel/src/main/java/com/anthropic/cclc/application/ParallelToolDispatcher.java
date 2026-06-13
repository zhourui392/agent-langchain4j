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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class ParallelToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ParallelToolDispatcher.class);

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
        requests.forEach(listener::onToolUseStart);
        log.info("dispatching tools: names={}, concurrency={}", toolNames(requests), requests.size());
        if (requests.size() == 1) {
            return List.of(executeSingle(requests.get(0), listener));
        }
        return executeAll(requests, listener);
    }

    private ToolResultMessage executeSingle(ToolUseRequest request, AgentEventListener listener) {
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        return withMdc(parentMdc, request, () -> {
            ToolResult result = runWithEvents(request, listener);
            return ToolResultMessage.of(request.id(), result.content());
        });
    }

    private List<ToolResultMessage> executeAll(List<ToolUseRequest> requests, AgentEventListener listener) {
        ConcurrentHashMap<ToolUseId, ToolResult> resultsById = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(requests.size());
            for (ToolUseRequest req : requests) {
                Map<String, String> parentMdc = MDC.getCopyOfContextMap();
                futures.add(pool.submit(() -> withMdc(parentMdc, req, () -> {
                    resultsById.put(req.id(), runWithEvents(req, listener));
                    return null;
                })));
            }
            awaitAll(futures);
        }
        return assembleInOrder(requests, resultsById);
    }

    private ToolResult runWithEvents(ToolUseRequest request, AgentEventListener listener) {
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
        log.info("permission decision: tool={}, decision={}", tool.name(), decision);
        if (decision == Decision.DENY) {
            log.warn("permission denied: {}", tool.name());
            invocation.deny();
            return ToolResult.error("permission denied: " + tool.name());
        }
        invocation.allow();
        return runTool(tool, invocation);
    }

    private ToolResult runTool(Tool tool, ToolInvocation invocation) {
        long startNs = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("tool arguments summary: tool={}, args={}", tool.name(), summarizeArgs(invocation.args()));
        }
        try {
            ToolResult result = tool.execute(invocation.args(), executionContext);
            invocation.complete(result);
            log.info("tool completed: tool={}, success={}, durationMs={}",
                    tool.name(), result.success(), elapsedMs(startNs));
            return result;
        } catch (RuntimeException ex) {
            ToolResult failure = ToolResult.error(ex.getMessage());
            invocation.fail(failure);
            log.error("tool failed: tool={}, errorType={}, message={}",
                    tool.name(), ex.getClass().getSimpleName(), ex.getMessage());
            log.debug("tool failure stack", ex);
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
            log.debug("assembling tool result: toolUseId={}, toolName={}, present={}",
                    req.id().value(), req.toolName(), result != null);
            ordered.add(ToolResultMessage.of(req.id(), result.content()));
        }
        return ordered;
    }

    private static <T> T withMdc(Map<String, String> parentMdc, ToolUseRequest request,
                                 java.util.function.Supplier<T> action) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try {
            if (parentMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(parentMdc);
            }
            MDC.put("toolUseId", request.id().value());
            return action.get();
        } finally {
            if (previousMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousMdc);
            }
        }
    }

    private static List<String> toolNames(List<ToolUseRequest> requests) {
        return requests.stream().map(ToolUseRequest::toolName).toList();
    }

    private static String summarizeArgs(com.anthropic.cclc.domain.tool.ToolArguments args) {
        return args.values().keySet().toString();
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
