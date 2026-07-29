package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.domain.tool.UnknownToolException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
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
    private final RunId runId;
    private final ExecutionContext executionContext;
    private final PermissionService permissions;

    ParallelToolDispatcher(ToolRegistry tools, RunId runId,
                           ExecutionContext executionContext, PermissionService permissions) {
        this.tools = tools;
        this.runId = runId;
        this.executionContext = executionContext;
        this.permissions = permissions;
    }

    List<ToolResultMessage> dispatch(AiMessage aiMessage) {
        return dispatch(aiMessage, AgentEventListener.NO_OP);
    }

    List<ToolResultMessage> dispatch(AiMessage aiMessage, AgentEventListener listener) {
        List<ToolUseRequest> requests = aiMessage.toolUseRequests();
        requests.forEach(request -> notifyStart(listener, request));
        log.info("dispatching tools: names={}, concurrency={}", toolNames(requests), requests.size());
        if (requests.size() == 1) {
            return List.of(executeSingle(requests.get(0), listener));
        }
        return executeAll(requests, listener);
    }

    private ToolResultMessage executeSingle(ToolUseRequest request, AgentEventListener listener) {
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        return withMdc(parentMdc, request,
                () -> ToolResultMessage.from(request.id(), runWithEvents(request, listener)));
    }

    private List<ToolResultMessage> executeAll(List<ToolUseRequest> requests, AgentEventListener listener) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolResultMessage>> futures = new ArrayList<>(requests.size());
            for (ToolUseRequest req : requests) {
                Map<String, String> parentMdc = MDC.getCopyOfContextMap();
                futures.add(pool.submit(() -> withMdc(parentMdc, req,
                        () -> ToolResultMessage.from(req.id(), runWithEvents(req, listener)))));
            }
            return awaitAll(requests, futures);
        }
    }

    List<ToolResultMessage> settleWithoutExecution(
            AiMessage aiMessage, ToolResultStatus status, String reason) {
        return aiMessage.toolUseRequests().stream()
                .map(request -> ToolResultMessage.of(request.id(), status, reason, Map.of()))
                .toList();
    }

    private ToolResult runWithEvents(ToolUseRequest request, AgentEventListener listener) {
        long startNs = System.nanoTime();
        ToolResult result = safeOutcome(request);
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        notifyEnd(listener, request, result, durationMs);
        return result;
    }

    private ToolResult safeOutcome(ToolUseRequest request) {
        Tool tool;
        try {
            tool = tools.find(request.toolName());
        } catch (UnknownToolException ex) {
            return failure(ToolResultStatus.UNKNOWN_TOOL, ex, "lookup");
        }
        ToolInvocation invocation;
        try {
            invocation = InvocationFactory.from(request);
        } catch (IllegalArgumentException ex) {
            return failure(ToolResultStatus.INVALID_ARGUMENTS, ex, "arguments");
        }
        try {
            return runWithPermission(tool, invocation);
        } catch (CancellationException ex) {
            return failure(ToolResultStatus.CANCELLED, ex, "permission");
        } catch (RuntimeException ex) {
            return failure(ToolResultStatus.ERROR, ex, "permission");
        }
    }

    private ToolResult runWithPermission(Tool tool, ToolInvocation invocation) {
        Decision decision = permissions.check(executionContext, invocation, tool);
        log.info("permission decision: tool={}, decision={}", tool.name(), decision);
        if (decision == Decision.DENY) {
            log.warn("permission denied: {}", tool.name());
            ToolResult denied = ToolResult.of(
                    ToolResultStatus.DENIED, "permission denied: " + tool.name());
            invocation.settle(denied);
            return denied;
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
            invocation.settle(result);
            log.info("tool completed: tool={}, success={}, durationMs={}",
                    tool.name(), result.success(), elapsedMs(startNs));
            return result;
        } catch (CancellationException ex) {
            ToolResult cancelled = failure(ToolResultStatus.CANCELLED, ex, "execution");
            invocation.settle(cancelled);
            return cancelled;
        } catch (RuntimeException ex) {
            ToolResult failure = failure(ToolResultStatus.ERROR, ex, "execution");
            invocation.settle(failure);
            log.error("tool failed: tool={}, errorType={}, message={}",
                    tool.name(), ex.getClass().getSimpleName(), ex.getMessage());
            log.debug("tool failure stack", ex);
            return failure;
        }
    }

    private static List<ToolResultMessage> awaitAll(
            List<ToolUseRequest> requests, List<Future<ToolResultMessage>> futures) {
        List<ToolResultMessage> results = new ArrayList<>(futures.size());
        for (int index = 0; index < futures.size(); index++) {
            try {
                results.add(futures.get(index).get());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                results.add(cancelled(requests.get(index), ie));
            } catch (ExecutionException ee) {
                results.add(failed(requests.get(index), ee.getCause()));
            }
        }
        return List.copyOf(results);
    }

    private static ToolResultMessage cancelled(ToolUseRequest request, InterruptedException failure) {
        ToolResult result = failure(ToolResultStatus.CANCELLED, failure, "dispatch");
        return ToolResultMessage.from(request.id(), result);
    }

    private static ToolResultMessage failed(ToolUseRequest request, Throwable cause) {
        ToolResult result = ToolResult.of(ToolResultStatus.ERROR,
                messageOf(cause), Map.of("stage", "dispatch"));
        return ToolResultMessage.from(request.id(), result);
    }

    private static ToolResult failure(ToolResultStatus status, Throwable failure, String stage) {
        return ToolResult.of(status, messageOf(failure), Map.of("stage", stage));
    }

    private static String messageOf(Throwable failure) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return failure == null ? "tool execution failed" : failure.getClass().getSimpleName();
        }
        return failure.getMessage();
    }

    private static void notifyStart(AgentEventListener listener, ToolUseRequest request) {
        try {
            listener.onToolUseStart(request);
        } catch (RuntimeException ex) {
            log.warn("tool start listener failed: toolUseId={}", request.id(), ex);
        }
    }

    private static void notifyEnd(AgentEventListener listener, ToolUseRequest request,
                                  ToolResult result, long durationMs) {
        try {
            listener.onToolUseEnd(request, result, durationMs);
        } catch (RuntimeException ex) {
            log.warn("tool end listener failed: toolUseId={}", request.id(), ex);
        }
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

    private static String summarizeArgs(com.anthropic.agentkit.domain.tool.ToolArguments args) {
        return args.values().keySet().toString();
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
