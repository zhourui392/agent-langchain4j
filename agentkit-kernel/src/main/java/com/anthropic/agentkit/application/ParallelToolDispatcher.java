package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.interception.AgentInterceptorException;
import com.anthropic.agentkit.application.interception.AgentInterceptors;
import com.anthropic.agentkit.application.interception.ToolDispatchContext;
import com.anthropic.agentkit.application.interception.ToolDispatchDecision;
import com.anthropic.agentkit.application.interception.ToolSettled;
import com.anthropic.agentkit.application.tool.ToolOutputPolicy;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.port.RunEventPersistenceException;
import com.anthropic.agentkit.domain.suspension.ApprovalDecision;
import com.anthropic.agentkit.domain.suspension.ApprovalRequest;
import com.anthropic.agentkit.domain.suspension.PlannedToolInvocation;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.UnknownToolException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class ParallelToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ParallelToolDispatcher.class);

    private final ToolRegistry tools;
    private final ExecutionContext executionContext;
    private final PermissionService permissions;
    private final ToolOutputPolicy outputPolicy;
    private final RunEventRecorder eventRecorder;
    private final AgentInterceptors interceptors;

    ParallelToolDispatcher(ToolRegistry tools, ExecutionContext executionContext,
                           PermissionService permissions) {
        this(tools, executionContext, permissions, ToolOutputPolicy.defaultLimited());
    }

    ParallelToolDispatcher(ToolRegistry tools, ExecutionContext executionContext,
                           PermissionService permissions, ToolOutputPolicy outputPolicy) {
        this(tools, executionContext, permissions, outputPolicy, RunEventRecorder.disabled());
    }

    ParallelToolDispatcher(ToolRegistry tools, ExecutionContext executionContext,
                           PermissionService permissions, ToolOutputPolicy outputPolicy,
                           RunEventRecorder eventRecorder) {
        this(tools, executionContext, permissions, outputPolicy,
                eventRecorder, AgentInterceptors.none());
    }

    ParallelToolDispatcher(ToolRegistry tools, ExecutionContext executionContext,
                           PermissionService permissions, ToolOutputPolicy outputPolicy,
                           RunEventRecorder eventRecorder,
                           AgentInterceptors interceptors) {
        this.tools = tools;
        this.executionContext = executionContext;
        this.permissions = permissions;
        this.outputPolicy = outputPolicy;
        this.eventRecorder = eventRecorder;
        this.interceptors = interceptors;
    }

    List<ToolResultMessage> dispatch(AiMessage aiMessage) {
        return dispatch(aiMessage, AgentEventListener.NO_OP);
    }

    List<ToolResultMessage> dispatch(AiMessage aiMessage, AgentEventListener listener) {
        return dispatchOutcome(aiMessage, listener).results();
    }

    ToolDispatchBatch dispatchOutcome(
            AiMessage aiMessage, AgentEventListener listener) {
        return dispatchOutcome(aiMessage, listener, Map.of());
    }

    ToolDispatchBatch dispatchApproved(
            AiMessage aiMessage, AgentEventListener listener,
            ApprovalRequest request, ApprovalDecision response) {
        return dispatchOutcome(aiMessage, listener, permissionPlan(request, response));
    }

    ToolDispatchBatch dispatchPlanned(
            AiMessage aiMessage, AgentEventListener listener,
            ToolPermissionPlan plan) {
        return dispatchOutcome(
                aiMessage, listener, permissionPlan(plan.invocations()));
    }

    private ToolDispatchBatch dispatchOutcome(
            AiMessage aiMessage, AgentEventListener listener,
            Map<ToolUseId, Decision> permissionPlan) {
        AgentEventListener safeListener = SafeAgentEventListener.protect(listener);
        List<ToolUseRequest> requests = aiMessage.toolUseRequests();
        requests.forEach(safeListener::onToolUseStart);
        log.info("dispatching tools: names={}, concurrency={}", toolNames(requests), requests.size());
        if (requests.size() == 1) {
            return ToolDispatchBatch.from(List.of(
                    executeSingle(requests.get(0), safeListener, permissionPlan)));
        }
        return ToolDispatchBatch.from(executeAll(requests, safeListener, permissionPlan));
    }

    private SettledTool executeSingle(
            ToolUseRequest request, AgentEventListener listener,
            Map<ToolUseId, Decision> permissionPlan) {
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        return withMdc(parentMdc, request,
                () -> runWithEvents(request, listener, permissionPlan));
    }

    private List<SettledTool> executeAll(
            List<ToolUseRequest> requests, AgentEventListener listener,
            Map<ToolUseId, Decision> permissionPlan) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SettledTool>> futures = new ArrayList<>(requests.size());
            for (ToolUseRequest req : requests) {
                Map<String, String> parentMdc = MDC.getCopyOfContextMap();
                futures.add(pool.submit(() -> withMdc(parentMdc, req,
                        () -> runWithEvents(req, listener, permissionPlan))));
            }
            return awaitAll(requests, futures);
        }
    }

    List<ToolResultMessage> settleWithoutExecution(
            AiMessage aiMessage, ToolResultStatus status, String reason) {
        return aiMessage.toolUseRequests().stream()
                .map(request -> settleWithoutExecution(request, status, reason))
                .toList();
    }

    private ToolResultMessage settleWithoutExecution(
            ToolUseRequest request, ToolResultStatus status, String reason) {
        ToolResult result = ToolResult.of(status, reason);
        eventRecorder.toolInvocationSettled(request.id(), result);
        return ToolResultMessage.from(request.id(), result);
    }

    private SettledTool runWithEvents(
            ToolUseRequest request, AgentEventListener listener,
            Map<ToolUseId, Decision> permissionPlan) {
        long startNs = System.nanoTime();
        ToolExecutionOutcome outcome = safeOutcome(request, permissionPlan);
        ToolResult result = outcome.result();
        eventRecorder.toolInvocationSettled(request.id(), result);
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        listener.onToolUseEnd(request, result, durationMs);
        outcome.dispatch().ifPresent(context ->
                interceptors.afterToolSettled(new ToolSettled(context, result)));
        return new SettledTool(
                ToolResultMessage.from(request.id(), result), outcome.interceptorFailure());
    }

    private ToolExecutionOutcome safeOutcome(
            ToolUseRequest request, Map<ToolUseId, Decision> permissionPlan) {
        Tool tool;
        try {
            tool = tools.find(request.toolName(), executionContext);
        } catch (UnknownToolException ex) {
            return ToolExecutionOutcome.completed(
                    failure(ToolResultStatus.UNKNOWN_TOOL, ex, "lookup"));
        }
        ToolInvocation invocation;
        try {
            invocation = InvocationFactory.from(request);
        } catch (IllegalArgumentException ex) {
            return ToolExecutionOutcome.completed(
                    failure(ToolResultStatus.INVALID_ARGUMENTS, ex, "arguments"));
        }
        ToolDispatchContext dispatch = ToolDispatchContext.from(
                executionContext, invocation, tool);
        try {
            ToolDispatchDecision decision = interceptors.beforeToolDispatch(dispatch);
            if (decision instanceof ToolDispatchDecision.Deny denial) {
                ToolResult denied = ToolResult.of(
                        ToolResultStatus.DENIED, denial.reason(),
                        Map.of("stage", "interceptor"));
                return ToolExecutionOutcome.completed(
                        governAndSettle(invocation, denied), dispatch);
            }
            return ToolExecutionOutcome.completed(
                    runWithPermission(tool, invocation, permissionPlan), dispatch);
        } catch (AgentInterceptorException failure) {
            ToolResult result = ToolResult.of(
                    ToolResultStatus.INTERCEPTOR_ERROR, failure.getMessage(),
                    Map.of("stage", "interceptor",
                            "hook", failure.hook().methodName()));
            return ToolExecutionOutcome.interceptorFailed(
                    governAndSettle(invocation, result), dispatch, failure);
        } catch (CancellationException ex) {
            return ToolExecutionOutcome.completed(
                    failure(ToolResultStatus.CANCELLED, ex, "permission"));
        } catch (RuntimeException ex) {
            rethrowPersistence(ex);
            return ToolExecutionOutcome.completed(
                    failure(ToolResultStatus.ERROR, ex, "permission"));
        }
    }

    private ToolResult runWithPermission(
            Tool tool, ToolInvocation invocation,
            Map<ToolUseId, Decision> permissionPlan) {
        Decision decision = permissionPlan.get(invocation.id());
        if (decision == null) {
            decision = permissions.check(executionContext, invocation, tool);
        }
        log.info("permission decision: tool={}, decision={}", tool.name(), decision);
        if (decision == Decision.DENY) {
            log.warn("permission denied: {}", tool.name());
            ToolResult denied = ToolResult.of(
                    ToolResultStatus.DENIED, "permission denied: " + tool.name());
            return governAndSettle(invocation, denied);
        }
        invocation.allow();
        return runTool(tool, invocation);
    }

    private static Map<ToolUseId, Decision> permissionPlan(
            ApprovalRequest request, ApprovalDecision response) {
        Map<ToolUseId, Decision> plan = new java.util.LinkedHashMap<>();
        for (PlannedToolInvocation item : request.invocations()) {
            Decision decision = response == ApprovalDecision.DENY
                    ? Decision.DENY : approved(item.decision());
            plan.put(item.request().id(), decision);
        }
        return Map.copyOf(plan);
    }

    private static Map<ToolUseId, Decision> permissionPlan(
            List<PlannedToolInvocation> invocations) {
        Map<ToolUseId, Decision> plan = new java.util.LinkedHashMap<>();
        for (PlannedToolInvocation item : invocations) {
            plan.put(item.request().id(), item.decision());
        }
        return Map.copyOf(plan);
    }

    private static Decision approved(Decision planned) {
        return planned == Decision.ASK ? Decision.ALLOW : planned;
    }

    private ToolResult runTool(Tool tool, ToolInvocation invocation) {
        long startNs = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("tool arguments summary: tool={}, args={}", tool.name(), summarizeArgs(invocation.args()));
        }
        try {
            eventRecorder.toolInvocationStarted(invocation.id());
            ToolResult result = executeBounded(tool, invocation);
            result = governAndSettle(invocation, result);
            log.info("tool completed: tool={}, success={}, durationMs={}",
                    tool.name(), result.success(), elapsedMs(startNs));
            return result;
        } catch (CancellationException ex) {
            ToolResult cancelled = failure(ToolResultStatus.CANCELLED, ex, "execution");
            return governAndSettle(invocation, cancelled);
        } catch (RuntimeException ex) {
            rethrowPersistence(ex);
            ToolResult failure = failure(ToolResultStatus.ERROR, ex, "execution");
            failure = governAndSettle(invocation, failure);
            log.error("tool failed: tool={}, errorType={}, message={}",
                    tool.name(), ex.getClass().getSimpleName(), ex.getMessage());
            log.debug("tool failure stack", ex);
            return failure;
        }
    }

    private ToolResult governAndSettle(ToolInvocation invocation, ToolResult raw) {
        ToolResult governed;
        try {
            governed = java.util.Objects.requireNonNull(
                    outputPolicy.govern(invocation, raw, executionContext),
                    "tool output policy returned null");
        } catch (RuntimeException ex) {
            log.error("tool output policy failed: tool={}", invocation.toolName(), ex);
            governed = ToolResult.of(
                    ToolResultStatus.ERROR, "tool output rejected by policy",
                    Map.of("stage", "output_policy"));
        }
        invocation.settle(governed);
        return governed;
    }

    private ToolResult executeBounded(Tool tool, ToolInvocation invocation) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        Future<ToolResult> future = executor.submit(() -> withMdc(
                parentMdc, invocation.id(),
                () -> tool.execute(invocation.args(), executionContext)));
        try (var ignored = executionContext.cancellation().onCancel(
                () -> future.cancel(true))) {
            long waitNanos = executionContext.limits().toolWait().toNanos();
            if (waitNanos <= 0) {
                return timedOut(future);
            }
            return future.get(waitNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            return timedOut(future);
        } catch (CancellationException ex) {
            return ToolResult.of(ToolResultStatus.CANCELLED, "tool cancelled");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.of(ToolResultStatus.CANCELLED, "tool interrupted");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof CancellationException) {
                return ToolResult.of(ToolResultStatus.CANCELLED, messageOf(ex.getCause()));
            }
            return ToolResult.of(ToolResultStatus.ERROR, messageOf(ex.getCause()));
        } finally {
            executor.shutdownNow();
        }
    }

    private ToolResult timedOut(Future<ToolResult> future) {
        future.cancel(true);
        long timeoutMs = executionContext.limits().toolWait().toMillis();
        return ToolResult.of(ToolResultStatus.TIMEOUT,
                "tool timed out after " + timeoutMs + "ms");
    }

    private static List<SettledTool> awaitAll(
            List<ToolUseRequest> requests, List<Future<SettledTool>> futures) {
        List<SettledTool> results = new ArrayList<>(futures.size());
        for (int index = 0; index < futures.size(); index++) {
            try {
                results.add(futures.get(index).get());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                results.add(new SettledTool(
                        cancelled(requests.get(index), ie), Optional.empty()));
            } catch (ExecutionException ee) {
                rethrowPersistence(ee.getCause());
                results.add(new SettledTool(
                        failed(requests.get(index), ee.getCause()), Optional.empty()));
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

    private static void rethrowPersistence(Throwable failure) {
        if (failure instanceof RunEventPersistenceException persistence) {
            throw persistence;
        }
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

    private static <T> T withMdc(Map<String, String> parentMdc, ToolUseRequest request,
                                 java.util.function.Supplier<T> action) {
        return withMdc(parentMdc, request.id(), action);
    }

    private static <T> T withMdc(Map<String, String> parentMdc,
                                 com.anthropic.agentkit.domain.tool.ToolUseId toolUseId,
                                 java.util.function.Supplier<T> action) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try {
            if (parentMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(parentMdc);
            }
            MDC.put("toolUseId", toolUseId.value());
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

    record ToolDispatchBatch(
            List<ToolResultMessage> results,
            Optional<AgentInterceptorException> interceptorFailure) {

        ToolDispatchBatch {
            results = List.copyOf(results);
            interceptorFailure = java.util.Objects.requireNonNull(
                    interceptorFailure, "interceptorFailure");
        }

        private static ToolDispatchBatch from(List<SettledTool> settled) {
            List<ToolResultMessage> results = settled.stream()
                    .map(SettledTool::message).toList();
            Optional<AgentInterceptorException> failure = settled.stream()
                    .map(SettledTool::interceptorFailure)
                    .flatMap(Optional::stream).findFirst();
            return new ToolDispatchBatch(results, failure);
        }
    }

    private record SettledTool(
            ToolResultMessage message,
            Optional<AgentInterceptorException> interceptorFailure) {

        private SettledTool {
            java.util.Objects.requireNonNull(message, "message");
            java.util.Objects.requireNonNull(interceptorFailure, "interceptorFailure");
        }
    }

    private record ToolExecutionOutcome(
            ToolResult result,
            Optional<ToolDispatchContext> dispatch,
            Optional<AgentInterceptorException> interceptorFailure) {

        private ToolExecutionOutcome {
            java.util.Objects.requireNonNull(result, "result");
            java.util.Objects.requireNonNull(dispatch, "dispatch");
            java.util.Objects.requireNonNull(
                    interceptorFailure, "interceptorFailure");
            if (interceptorFailure.isPresent() && dispatch.isEmpty()) {
                throw new IllegalArgumentException(
                        "an interceptor failure requires a parsed dispatch context");
            }
        }

        private static ToolExecutionOutcome completed(ToolResult result) {
            return new ToolExecutionOutcome(
                    result, Optional.empty(), Optional.empty());
        }

        private static ToolExecutionOutcome completed(
                ToolResult result, ToolDispatchContext dispatch) {
            return new ToolExecutionOutcome(
                    result, Optional.of(dispatch), Optional.empty());
        }

        private static ToolExecutionOutcome interceptorFailed(
                ToolResult result, ToolDispatchContext dispatch,
                AgentInterceptorException failure) {
            return new ToolExecutionOutcome(
                    result, Optional.of(dispatch), Optional.of(failure));
        }
    }
}
