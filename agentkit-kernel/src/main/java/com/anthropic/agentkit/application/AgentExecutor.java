package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentBudgetExceededException;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmClient.StreamHandler;
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

    public AgentExecutor(LlmClient llm, ToolRegistry tools, PermissionService permissions) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
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
        return CompletableFuture.supplyAsync(() -> loop(conversation, context, listener, systemPrompt));
    }

    private AgentRunResult loop(Conversation conversation, AgentRunContext context,
                                AgentEventListener listener, String systemPrompt) {
        MDC.put("session", conversation.sessionId().value());
        MDC.put("run", context.runId().value());
        listener.onRunStart(context);
        log.info("session started: runId={}, workspaceId={}, initialMessages={}, systemPromptChars={}",
                context.runId(), context.workspaceId(), conversation.messages().size(), systemPrompt.length());
        AgentRunState state = AgentRunState.create(context, tools, permissions);
        try {
            return runLoop(conversation, state, listener, systemPrompt);
        } catch (AgentBudgetExceededException ex) {
            log.warn("session stopped: budget exhausted: {}", ex.getMessage());
            return state.finish(StopReason.BUDGET_EXHAUSTED);
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
                    conversation, state.context(), listener, state.budget(), systemPrompt);
            if (turnOutcome.stopReason().isPresent()) {
                return state.finishWithError(
                        turnOutcome.stopReason().orElseThrow(), turnOutcome.errorDetail());
            }
            AiMessage aiMessage = turnOutcome.message().orElseThrow();
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
                return state.finish(outcome.stopReason().orElseThrow(), outcome.structuredOutput());
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
            List<ToolResultMessage> results = state.dispatcher().dispatch(message, listener);
            return terminalOutcome(message, results);
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
                                       String systemPrompt) {
        long startNs = System.nanoTime();
        log.info("llm request started: messages={}, tools={}", conversation.messages().size(), tools.specs().size());
        listener.onLlmRequestStart();
        ChatRequest.Builder builder = ChatRequest.builder()
                .systemPrompt(systemPrompt)
                .messages(conversation.messages());
        tools.specs().forEach(builder::tool);
        ChatRequest request = builder.build();
        RunStreamHandler handler = new RunStreamHandler(context, listener, budgetGuard);
        LlmCall call;
        try {
            call = llm.streamChat(request, handler);
        } catch (RuntimeException failure) {
            handler.close();
            log.error("llm request failed before call handle was returned", failure);
            return LlmTurnOutcome.failed(StopReason.PROVIDER_ERROR, messageOf(failure));
        }
        try {
            LlmTurnOutcome result = awaitLlm(call, context, budgetGuard);
            log.info("llm request finished: durationMs={}, stopReason={}",
                    elapsedMs(startNs), result.stopReason().orElse(null));
            return result;
        } finally {
            handler.close();
        }
    }

    private LlmTurnOutcome awaitLlm(
            LlmCall call, AgentRunContext context, AgentBudgetGuard budgetGuard) {
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
        return LlmTurnOutcome.failed(StopReason.PROVIDER_ERROR, messageOf(failure));
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
        if (context.cancellation().isCancelled()) {
            return Optional.of(StopReason.CANCELLED);
        }
        if (context.limits().deadline().isExpired()) {
            return Optional.of(StopReason.TIMED_OUT);
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
        private AiMessage lastMessage = AiMessage.text("");

        private AgentRunState(AgentRunContext context, AgentBudgetGuard budget,
                              ParallelToolDispatcher dispatcher) {
            this.context = context;
            this.budget = budget;
            this.dispatcher = dispatcher;
        }

        private static AgentRunState create(AgentRunContext context, ToolRegistry tools,
                                            PermissionService permissions) {
            AgentBudgetGuard budget = new AgentBudgetGuard(
                    context.budget(), context.budgetState());
            ParallelToolDispatcher dispatcher = new ParallelToolDispatcher(
                    tools, context.executionContext(), permissions);
            return new AgentRunState(context, budget, dispatcher);
        }

        private AgentRunContext context() { return context; }
        private AgentBudgetGuard budget() { return budget; }
        private ParallelToolDispatcher dispatcher() { return dispatcher; }

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
            return new AgentRunResult(context.runId(), reason, lastMessage,
                    structuredOutput, budget.usage(), budget.consumption(), Optional.empty());
        }
    }

    private record ToolBatchOutcome(
            List<ToolResultMessage> results,
            Optional<StopReason> stopReason,
            Optional<Map<String, Object>> structuredOutput) {

        private ToolBatchOutcome {
            results = List.copyOf(results);
            structuredOutput = structuredOutput.map(Map::copyOf);
        }

        private static ToolBatchOutcome continuing(List<ToolResultMessage> results) {
            return new ToolBatchOutcome(results, Optional.empty(), Optional.empty());
        }

        private static ToolBatchOutcome stopped(List<ToolResultMessage> results, StopReason reason) {
            return new ToolBatchOutcome(results, Optional.of(reason), Optional.empty());
        }

        private static ToolBatchOutcome terminal(
                List<ToolResultMessage> results, Map<String, Object> payload) {
            return new ToolBatchOutcome(
                    results, Optional.of(StopReason.TERMINAL_TOOL), Optional.of(payload));
        }
    }

    private record LlmTurnOutcome(
            Optional<AiMessage> message,
            Optional<StopReason> stopReason,
            Optional<String> errorDetail) {

        private static LlmTurnOutcome completed(AiMessage message) {
            return new LlmTurnOutcome(
                    Optional.of(message), Optional.empty(), Optional.empty());
        }

        private static LlmTurnOutcome stopped(StopReason reason) {
            return new LlmTurnOutcome(
                    Optional.empty(), Optional.of(reason), Optional.empty());
        }

        private static LlmTurnOutcome failed(StopReason reason, String detail) {
            return new LlmTurnOutcome(
                    Optional.empty(), Optional.of(reason), Optional.of(detail));
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
