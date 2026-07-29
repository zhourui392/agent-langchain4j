package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.context.ContextDecision;
import com.anthropic.agentkit.application.context.ContextPolicy;
import com.anthropic.agentkit.application.interception.AgentInterceptorException;
import com.anthropic.agentkit.application.interception.AgentInterceptors;
import com.anthropic.agentkit.application.interception.CompactionCause;
import com.anthropic.agentkit.application.interception.CompactionCompleted;
import com.anthropic.agentkit.application.interception.CompactionContext;
import com.anthropic.agentkit.application.interception.CompactionDecision;
import com.anthropic.agentkit.application.interception.LlmCallCompleted;
import com.anthropic.agentkit.application.interception.LlmCallContext;
import com.anthropic.agentkit.application.interception.LlmCallDecision;
import com.anthropic.agentkit.application.interception.RunStopContext;
import com.anthropic.agentkit.application.interception.RunStopDecision;
import com.anthropic.agentkit.application.tool.ToolOutputPolicy;
import com.anthropic.agentkit.domain.agent.AgentBudgetExceededException;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.CompactionBoundary;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmClient.StreamHandler;
import com.anthropic.agentkit.domain.port.RunEventPersistenceException;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolKind;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final PermissionService permissions;
    private final ContextPolicy contextPolicy;
    private final ToolOutputPolicy toolOutputPolicy;
    private final RunEventStore eventStore;
    private final AgentInterceptors interceptors;

    public AgentExecutor(LlmClient llm, ToolRegistry tools, PermissionService permissions) {
        this(llm, tools, permissions,
                ContextPolicy.standard(llm), ToolOutputPolicy.defaultLimited(),
                RunEventStore.none(), AgentInterceptors.none());
    }

    public AgentExecutor(LlmClient llm, ToolRegistry tools, PermissionService permissions,
                         RunEventStore eventStore) {
        this(llm, tools, permissions,
                ContextPolicy.standard(llm), ToolOutputPolicy.defaultLimited(), eventStore,
                AgentInterceptors.none());
    }

    public AgentExecutor(LlmClient llm, ToolRegistry tools, PermissionService permissions,
                         AgentInterceptors interceptors) {
        this(llm, tools, permissions,
                ContextPolicy.standard(llm), ToolOutputPolicy.defaultLimited(),
                RunEventStore.none(), interceptors);
    }

    public AgentExecutor(LlmClient llm, ToolRegistry tools, PermissionService permissions,
                         ContextPolicy contextPolicy, ToolOutputPolicy toolOutputPolicy) {
        this(llm, tools, permissions, contextPolicy, toolOutputPolicy,
                RunEventStore.none(), AgentInterceptors.none());
    }

    public AgentExecutor(LlmClient llm, ToolRegistry tools, PermissionService permissions,
                         ContextPolicy contextPolicy, ToolOutputPolicy toolOutputPolicy,
                         RunEventStore eventStore) {
        this(llm, tools, permissions, contextPolicy, toolOutputPolicy,
                eventStore, AgentInterceptors.none());
    }

    public AgentExecutor(LlmClient llm, ToolRegistry tools, PermissionService permissions,
                         ContextPolicy contextPolicy, ToolOutputPolicy toolOutputPolicy,
                         RunEventStore eventStore, AgentInterceptors interceptors) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.contextPolicy = Objects.requireNonNull(contextPolicy, "contextPolicy");
        this.toolOutputPolicy = Objects.requireNonNull(toolOutputPolicy, "toolOutputPolicy");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
    }

    public CompletableFuture<AgentRunResult> run(Conversation conversation, AgentRunContext context) {
        return run(conversation, context, AgentEventListener.NO_OP, "");
    }

    public CompletableFuture<AgentRunResult> run(Conversation conversation, AgentRunContext context,
                                                 AgentEventListener listener) {
        return run(conversation, context, listener, "");
    }

    public CompletableFuture<AgentRunResult> run(Conversation conversation, AgentRunContext context,
                                                 AgentEventListener listener, String systemPrompt) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        if (!conversation.sessionId().equals(context.sessionId())) {
            throw new IllegalArgumentException("run context session does not match conversation");
        }
        AgentEventListener safeListener = SafeAgentEventListener.protect(listener);
        return CompletableFuture.supplyAsync(
                () -> loop(conversation, context, safeListener, systemPrompt));
    }

    private AgentRunResult loop(Conversation conversation, AgentRunContext context,
                                AgentEventListener listener, String systemPrompt) {
        MDC.put("session", conversation.sessionId().value());
        MDC.put("run", context.runId().value());
        AgentRunState state = AgentRunState.create(
                context, tools, permissions, toolOutputPolicy, eventStore, interceptors);
        try {
            state.recorder().runStarted(conversation);
            listener.onRunStart(context);
            log.info("session started: runId={}, workspaceId={}, initialMessages={}, systemPromptChars={}",
                    context.runId(), context.workspaceId(), conversation.messages().size(), systemPrompt.length());
            AgentRunResult result = runWithBudgetHandling(
                    conversation, state, listener, systemPrompt);
            result = interceptRunStop(context, result);
            state.recorder().runStopped(result);
            return result;
        } catch (RunEventPersistenceException ex) {
            log.error("session stopped: run event persistence failed", ex);
            return state.finishWithError(
                    StopReason.PERSISTENCE_ERROR, Optional.of(messageOf(ex)));
        } catch (RuntimeException ex) {
            log.error("session failed", ex);
            listener.onError(ex);
            throw ex;
        } finally {
            permissions.clear(context.runId());
            MDC.remove("turn");
            MDC.remove("toolUseId");
            MDC.remove("run");
            MDC.remove("session");
        }
    }

    private AgentRunResult interceptRunStop(
            AgentRunContext context, AgentRunResult proposed) {
        try {
            RunStopDecision decision = interceptors.beforeRunStop(
                    new RunStopContext(context, proposed));
            if (decision instanceof RunStopDecision.Deny denial) {
                return rejectedStop(proposed, StopReason.INTERCEPTOR_DENIED, denial.reason());
            }
            return proposed;
        } catch (AgentInterceptorException failure) {
            return rejectedStop(
                    proposed, StopReason.INTERCEPTOR_ERROR, failure.getMessage());
        }
    }

    private static AgentRunResult rejectedStop(
            AgentRunResult proposed, StopReason reason, String detail) {
        return new AgentRunResult(
                proposed.runId(), reason, proposed.finalMessage(), Optional.empty(),
                proposed.usage(), proposed.consumption(), Optional.of(detail));
    }

    private AgentRunResult runWithBudgetHandling(
            Conversation conversation, AgentRunState state,
            AgentEventListener listener, String systemPrompt) {
        try {
            return runLoop(conversation, state, listener, systemPrompt);
        } catch (AgentBudgetExceededException ex) {
            log.warn("session stopped: budget exhausted: {}", ex.getMessage());
            return state.finish(StopReason.BUDGET_EXHAUSTED);
        }
    }

    private AgentRunResult runLoop(Conversation conversation, AgentRunState state,
                                   AgentEventListener listener, String systemPrompt) {
        int turn = 0;
        while (true) {
            Optional<StopReason> boundedStop = boundedStop(state.context());
            if (boundedStop.isPresent()) {
                return state.finish(boundedStop.orElseThrow());
            }
            state.budget().ensureInputTokensWithinBudget();
            state.budget().reserveTurn();
            turn++;
            MDC.put("turn", String.valueOf(turn));
            log.info("turn {} started: messageCount={}", turn, conversation.messages().size());
            LlmTurnOutcome turnOutcome = executeTurn(
                    conversation, state.context(), listener, state.budget(),
                    state.recorder(), systemPrompt);
            if (turnOutcome.stopReason().isPresent()) {
                return state.finishWithError(
                        turnOutcome.stopReason().orElseThrow(), turnOutcome.errorDetail());
            }
            AiMessage aiMessage = turnOutcome.message().orElseThrow();
            state.recorder().assistantTurnReceived(aiMessage);
            state.remember(aiMessage);
            appendMessage(conversation, aiMessage, "assistant message");
            if (!aiMessage.hasToolUseRequests()) {
                try {
                    state.budget().ensureInputTokensWithinBudget();
                } catch (AgentBudgetExceededException ex) {
                    return state.finish(StopReason.BUDGET_EXHAUSTED);
                }
                log.info("turn {} completed: stopReason=no_tool_use", turn);
                listener.onTurnComplete(aiMessage);
                return state.finish(StopReason.MODEL_COMPLETED);
            }
            ToolBatchOutcome outcome = dispatchToolCalls(aiMessage, state, listener);
            for (ToolResultMessage result : outcome.results()) {
                appendMessage(conversation, result, "tool result");
            }
            if (outcome.stopReason().isPresent()) {
                return state.finish(
                        outcome.stopReason().orElseThrow(), outcome.structuredOutput(),
                        outcome.errorDetail());
            }
            log.info("turn {} completed: toolResults={}", turn, aiMessage.toolUseRequests().size());
        }
    }

    private ToolBatchOutcome dispatchToolCalls(
            AiMessage message, AgentRunState state, AgentEventListener listener) {
        if (violatesTerminalExclusivity(message)) {
            String reason = "terminal tool must be exclusive in its tool batch";
            List<ToolResultMessage> results = state.dispatcher().settleWithoutExecution(
                    message, ToolResultStatus.ERROR, reason);
            return ToolBatchOutcome.stopped(results, StopReason.TOOL_PROTOCOL_ERROR);
        }
        try {
            state.budget().ensureInputTokensWithinBudget();
            state.budget().reserveToolCalls(message.toolUseRequests().size());
            ParallelToolDispatcher.ToolDispatchBatch batch =
                    state.dispatcher().dispatchOutcome(message, listener);
            if (batch.interceptorFailure().isPresent()) {
                return ToolBatchOutcome.stopped(
                        batch.results(), StopReason.INTERCEPTOR_ERROR,
                        batch.interceptorFailure().orElseThrow().getMessage());
            }
            return terminalOutcome(message, batch.results());
        } catch (AgentBudgetExceededException ex) {
            log.warn("tool batch rejected by budget: {}", ex.getMessage());
            List<ToolResultMessage> results = state.dispatcher().settleWithoutExecution(
                    message, ToolResultStatus.BUDGET_EXHAUSTED, ex.getMessage());
            return ToolBatchOutcome.stopped(results, StopReason.BUDGET_EXHAUSTED);
        }
    }

    private ToolBatchOutcome terminalOutcome(
            AiMessage message, List<ToolResultMessage> results) {
        if (!isTerminal(message.toolUseRequests().getFirst())) {
            return ToolBatchOutcome.continuing(results);
        }
        ToolResultMessage terminalResult = results.getFirst();
        if (terminalResult.isError()) {
            return ToolBatchOutcome.continuing(results);
        }
        Map<String, Object> payload = InvocationFactory.from(
                message.toolUseRequests().getFirst()).args().values();
        return ToolBatchOutcome.terminal(results, payload);
    }

    private boolean violatesTerminalExclusivity(AiMessage message) {
        long terminalCount = message.toolUseRequests().stream()
                .filter(this::isTerminal)
                .count();
        return terminalCount > 0 && (terminalCount != 1 || message.toolUseRequests().size() != 1);
    }

    private boolean isTerminal(ToolUseRequest request) {
        return tools.contains(request.toolName())
                && tools.find(request.toolName()).kind() == ToolKind.TERMINAL;
    }

    private LlmTurnOutcome executeTurn(Conversation conversation, AgentRunContext context,
                                       AgentEventListener listener, AgentBudgetGuard budgetGuard,
                                       RunEventRecorder recorder, String systemPrompt) {
        ContextDecision preparation = prepareContext(conversation, context, recorder);
        if (preparation.stopReason().isPresent()) {
            return LlmTurnOutcome.from(preparation);
        }
        LlmTurnOutcome first = requestLlm(
                conversation, context, listener, budgetGuard, recorder, systemPrompt);
        if (first.failure().isEmpty()) {
            return first;
        }
        ContextDecision recovery = recoverContext(
                conversation, first.failure().orElseThrow(), context, recorder);
        if (recovery.notApplicable()) {
            return first;
        }
        if (recovery.stopReason().isPresent()) {
            return LlmTurnOutcome.from(recovery);
        }
        ContextDecision retryPreparation = prepareContext(conversation, context, recorder);
        if (retryPreparation.stopReason().isPresent()) {
            return LlmTurnOutcome.from(retryPreparation);
        }
        LlmTurnOutcome retry = requestLlm(
                conversation, context, listener, budgetGuard, recorder, systemPrompt);
        if (retry.failure().filter(contextPolicy::isContextOverflow).isPresent()) {
            return LlmTurnOutcome.failed(
                    StopReason.CONTEXT_EXHAUSTED, messageOf(retry.failure().orElseThrow()));
        }
        return retry;
    }

    private ContextDecision prepareContext(
            Conversation conversation, AgentRunContext context, RunEventRecorder recorder) {
        return applyContextPolicy(
                conversation, context, recorder, CompactionCause.BEFORE_LLM_CALL,
                () -> contextPolicy.beforeLlmCall(conversation, context));
    }

    private ContextDecision recoverContext(
            Conversation conversation, Throwable failure, AgentRunContext context,
            RunEventRecorder recorder) {
        return applyContextPolicy(
                conversation, context, recorder,
                CompactionCause.CONTEXT_OVERFLOW_RECOVERY,
                () -> contextPolicy.recoverFromOverflow(conversation, failure, context));
    }

    private ContextDecision applyContextPolicy(
            Conversation conversation, AgentRunContext context,
            RunEventRecorder recorder, CompactionCause cause,
            java.util.function.Supplier<ContextDecision> policy) {
        CompactionContext attempt = new CompactionContext(
                context, conversation.messages(), conversation.lastCompaction(), cause);
        ContextDecision blocked = interceptCompaction(attempt);
        if (blocked != null) {
            return blocked;
        }
        ContextDecision decision = policy.get();
        newCompaction(attempt.previousBoundary(), conversation, recorder)
                .ifPresent(boundary -> interceptors.afterCompaction(
                        new CompactionCompleted(attempt, boundary)));
        return decision;
    }

    private ContextDecision interceptCompaction(CompactionContext attempt) {
        try {
            CompactionDecision decision = interceptors.beforeCompaction(attempt);
            if (decision instanceof CompactionDecision.Deny denial) {
                return ContextDecision.stopped(
                        StopReason.INTERCEPTOR_DENIED, denial.reason());
            }
            return null;
        } catch (AgentInterceptorException failure) {
            return ContextDecision.stopped(
                    StopReason.INTERCEPTOR_ERROR, failure.getMessage());
        }
    }

    private Optional<CompactionBoundary> newCompaction(
            Optional<CompactionBoundary> before,
            Conversation conversation, RunEventRecorder recorder) {
        Optional<CompactionBoundary> installed = conversation.lastCompaction()
                .filter(boundary -> before.filter(boundary::equals).isEmpty());
        installed.ifPresent(boundary ->
                recorder.compactionCompleted(conversation, boundary));
        return installed;
    }

    private LlmTurnOutcome requestLlm(Conversation conversation, AgentRunContext context,
                                      AgentEventListener listener, AgentBudgetGuard budgetGuard,
                                      RunEventRecorder recorder, String systemPrompt) {
        ChatRequest proposed = buildChatRequest(conversation, systemPrompt);
        LlmRequestPlan plan = interceptLlmCall(context, proposed);
        if (plan.stopReason().isPresent()) {
            return LlmTurnOutcome.failed(
                    plan.stopReason().orElseThrow(), plan.errorDetail().orElseThrow());
        }
        ChatRequest request = plan.request().orElseThrow();
        long startNs = System.nanoTime();
        log.info("llm request started: messages={}, tools={}",
                request.messages().size(), request.tools().size());
        recorder.llmCallStarted(request.messages().size());
        listener.onLlmRequestStart();
        RunStreamHandler handler = new RunStreamHandler(context, listener, budgetGuard);
        LlmCall call;
        try {
            call = llm.streamChat(request, handler);
        } catch (RuntimeException failure) {
            handler.close();
            log.error("llm request failed before call handle was returned", failure);
            return observeLlmCall(
                    context, request, LlmTurnOutcome.providerFailure(failure));
        }
        try {
            LlmTurnOutcome result = observeLlmCall(
                    context, request, awaitLlm(call, context));
            log.info("llm request finished: durationMs={}, stopReason={}",
                    elapsedMs(startNs), result.stopReason().orElse(null));
            return result;
        } finally {
            handler.close();
        }
    }

    private ChatRequest buildChatRequest(
            Conversation conversation, String systemPrompt) {
        ChatRequest.Builder builder = ChatRequest.builder()
                .systemPrompt(systemPrompt)
                .messages(conversation.messages());
        tools.specs().forEach(builder::tool);
        return builder.build();
    }

    private LlmRequestPlan interceptLlmCall(
            AgentRunContext context, ChatRequest proposed) {
        try {
            LlmCallDecision decision = interceptors.beforeLlmCall(
                    new LlmCallContext(context, proposed));
            if (decision instanceof LlmCallDecision.Deny denial) {
                return LlmRequestPlan.stopped(
                        StopReason.INTERCEPTOR_DENIED, denial.reason());
            }
            if (decision instanceof LlmCallDecision.ReplaceContext replacement) {
                return LlmRequestPlan.ready(new ChatRequest(
                        proposed.systemPrompt(), replacement.messages(), proposed.tools()));
            }
            return LlmRequestPlan.ready(proposed);
        } catch (AgentInterceptorException failure) {
            return LlmRequestPlan.stopped(
                    StopReason.INTERCEPTOR_ERROR, failure.getMessage());
        }
    }

    private LlmTurnOutcome observeLlmCall(
            AgentRunContext context, ChatRequest request, LlmTurnOutcome outcome) {
        interceptors.afterLlmCall(new LlmCallCompleted(
                new LlmCallContext(context, request), outcome.message(),
                outcome.stopReason(), outcome.errorDetail()));
        return outcome;
    }

    private LlmTurnOutcome awaitLlm(LlmCall call, AgentRunContext context) {
        long waitNanos = context.limits().providerWait().toNanos();
        if (waitNanos <= 0) {
            call.cancel();
            return LlmTurnOutcome.stopped(StopReason.TIMED_OUT);
        }
        try (CancellationToken.Registration ignored =
                     context.cancellation().onCancel(call::cancel)) {
            AiMessage message = call.completion().toCompletableFuture()
                    .get(waitNanos, TimeUnit.NANOSECONDS);
            return LlmTurnOutcome.completed(message);
        } catch (TimeoutException ex) {
            call.cancel();
            return LlmTurnOutcome.stopped(StopReason.TIMED_OUT);
        } catch (CancellationException ex) {
            return LlmTurnOutcome.stopped(cancellationReason(context));
        } catch (ExecutionException ex) {
            return failedLlmOutcome(ex.getCause(), context);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            call.cancel();
            return LlmTurnOutcome.stopped(StopReason.CANCELLED);
        }
    }

    private LlmTurnOutcome failedLlmOutcome(Throwable failure, AgentRunContext context) {
        if (failure instanceof AgentBudgetExceededException) {
            return LlmTurnOutcome.stopped(StopReason.BUDGET_EXHAUSTED);
        }
        if (failure instanceof CancellationException) {
            return LlmTurnOutcome.stopped(cancellationReason(context));
        }
        log.error("llm stream failed", failure);
        return LlmTurnOutcome.providerFailure(failure);
    }

    private static String messageOf(Throwable failure) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return failure == null ? "provider failed" : failure.getClass().getSimpleName();
        }
        return failure.getMessage();
    }

    private static StopReason cancellationReason(AgentRunContext context) {
        return context.limits().deadline().isExpired()
                ? StopReason.TIMED_OUT : StopReason.CANCELLED;
    }

    private static Optional<StopReason> boundedStop(AgentRunContext context) {
        if (context.limits().deadline().isExpired()) {
            return Optional.of(StopReason.TIMED_OUT);
        }
        if (context.cancellation().isCancelled()) {
            return Optional.of(StopReason.CANCELLED);
        }
        return Optional.empty();
    }

    private static void appendMessage(Conversation conversation, com.anthropic.agentkit.domain.message.ChatMessage message,
                                      String description) {
        try {
            conversation.append(message);
        } catch (RuntimeException ex) {
            log.error("failed to append {}", description, ex);
            throw ex;
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private static final class AgentRunState {

        private final AgentRunContext context;
        private final AgentBudgetGuard budget;
        private final ParallelToolDispatcher dispatcher;
        private final RunEventRecorder recorder;
        private AiMessage lastMessage = AiMessage.text("");

        private AgentRunState(AgentRunContext context, AgentBudgetGuard budget,
                              ParallelToolDispatcher dispatcher, RunEventRecorder recorder) {
            this.context = context;
            this.budget = budget;
            this.dispatcher = dispatcher;
            this.recorder = recorder;
        }

        private static AgentRunState create(
                AgentRunContext context, ToolRegistry tools, PermissionService permissions,
                ToolOutputPolicy toolOutputPolicy, RunEventStore eventStore,
                AgentInterceptors interceptors) {
            AgentBudgetGuard budget = new AgentBudgetGuard(
                    context.budget(), context.budgetState());
            RunEventRecorder recorder = RunEventRecorder.forRun(eventStore, context);
            ParallelToolDispatcher dispatcher = new ParallelToolDispatcher(
                    tools, context.executionContext(), permissions, toolOutputPolicy,
                    recorder, interceptors);
            return new AgentRunState(context, budget, dispatcher, recorder);
        }

        private AgentRunContext context() { return context; }
        private AgentBudgetGuard budget() { return budget; }
        private ParallelToolDispatcher dispatcher() { return dispatcher; }
        private RunEventRecorder recorder() { return recorder; }

        private void remember(AiMessage message) {
            lastMessage = message;
        }

        private AgentRunResult finish(StopReason reason) {
            return finish(reason, Optional.empty());
        }

        private AgentRunResult finishWithError(
                StopReason reason, Optional<String> errorDetail) {
            return new AgentRunResult(context.runId(), reason, lastMessage,
                    Optional.empty(), budget.usage(), budget.consumption(), errorDetail);
        }

        private AgentRunResult finish(
                StopReason reason, Optional<Map<String, Object>> structuredOutput) {
            return finish(reason, structuredOutput, Optional.empty());
        }

        private AgentRunResult finish(
                StopReason reason, Optional<Map<String, Object>> structuredOutput,
                Optional<String> errorDetail) {
            return new AgentRunResult(context.runId(), reason, lastMessage,
                    structuredOutput, budget.usage(), budget.consumption(), errorDetail);
        }
    }

    private record ToolBatchOutcome(
            List<ToolResultMessage> results,
            Optional<StopReason> stopReason,
            Optional<Map<String, Object>> structuredOutput,
            Optional<String> errorDetail) {

        private ToolBatchOutcome {
            results = List.copyOf(results);
            structuredOutput = structuredOutput.map(Map::copyOf);
            errorDetail = Objects.requireNonNull(errorDetail, "errorDetail");
        }

        private static ToolBatchOutcome continuing(List<ToolResultMessage> results) {
            return new ToolBatchOutcome(
                    results, Optional.empty(), Optional.empty(), Optional.empty());
        }

        private static ToolBatchOutcome stopped(List<ToolResultMessage> results, StopReason reason) {
            return new ToolBatchOutcome(
                    results, Optional.of(reason), Optional.empty(), Optional.empty());
        }

        private static ToolBatchOutcome stopped(
                List<ToolResultMessage> results, StopReason reason, String detail) {
            return new ToolBatchOutcome(
                    results, Optional.of(reason), Optional.empty(), Optional.of(detail));
        }

        private static ToolBatchOutcome terminal(
                List<ToolResultMessage> results, Map<String, Object> payload) {
            return new ToolBatchOutcome(
                    results, Optional.of(StopReason.TERMINAL_TOOL),
                    Optional.of(payload), Optional.empty());
        }
    }

    private record LlmRequestPlan(
            Optional<ChatRequest> request,
            Optional<StopReason> stopReason,
            Optional<String> errorDetail) {

        private static LlmRequestPlan ready(ChatRequest request) {
            return new LlmRequestPlan(
                    Optional.of(request), Optional.empty(), Optional.empty());
        }

        private static LlmRequestPlan stopped(StopReason reason, String detail) {
            return new LlmRequestPlan(
                    Optional.empty(), Optional.of(reason), Optional.of(detail));
        }
    }

    private record LlmTurnOutcome(
            Optional<AiMessage> message,
            Optional<StopReason> stopReason,
            Optional<String> errorDetail,
            Optional<Throwable> failure) {

        private static LlmTurnOutcome completed(AiMessage message) {
            return new LlmTurnOutcome(
                    Optional.of(message), Optional.empty(), Optional.empty(), Optional.empty());
        }

        private static LlmTurnOutcome stopped(StopReason reason) {
            return new LlmTurnOutcome(
                    Optional.empty(), Optional.of(reason), Optional.empty(), Optional.empty());
        }

        private static LlmTurnOutcome failed(StopReason reason, String detail) {
            return new LlmTurnOutcome(
                    Optional.empty(), Optional.of(reason), Optional.of(detail), Optional.empty());
        }

        private static LlmTurnOutcome providerFailure(Throwable failure) {
            return new LlmTurnOutcome(
                    Optional.empty(), Optional.of(StopReason.PROVIDER_ERROR),
                    Optional.of(messageOf(failure)), Optional.of(failure));
        }

        private static LlmTurnOutcome from(ContextDecision decision) {
            return new LlmTurnOutcome(
                    Optional.empty(), decision.stopReason(),
                    decision.errorDetail(), Optional.empty());
        }
    }

    private static final class RunStreamHandler implements StreamHandler, AutoCloseable {
        private final AgentRunContext context;
        private final AgentEventListener listener;
        private final AgentBudgetGuard budget;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private int fragments;
        private int characters;

        private RunStreamHandler(AgentRunContext context, AgentEventListener listener,
                                 AgentBudgetGuard budget) {
            this.context = context;
            this.listener = listener;
            this.budget = budget;
        }

        @Override
        public void onPartialText(String delta) {
            if (!open.get()) {
                return;
            }
            context.cancellation().throwIfCancelled();
            budget.recordOutputCharacters(delta.length());
            budget.ensureInputTokensWithinBudget();
            fragments++;
            characters += delta.length();
            log.debug("llm partial text received: fragment={}, chars={}, totalChars={}",
                    fragments, delta.length(), characters);
            listener.onAssistantTextDelta(delta);
        }

        @Override
        public void onUsage(int input, int output, int cacheRead) {
            if (!open.get()) {
                return;
            }
            budget.recordUsage(input, output, cacheRead);
            listener.onUsage(input, output, cacheRead);
        }

        @Override
        public void onComplete(AiMessage message) {
            if (open.get()) {
                log.info("llm completed: toolUseCount={}", message.toolUseRequests().size());
            }
        }

        @Override
        public void onError(Throwable error) {
            if (open.get()) {
                log.error("llm stream failed", error);
            }
        }

        @Override
        public void close() {
            open.set(false);
        }
    }

}
