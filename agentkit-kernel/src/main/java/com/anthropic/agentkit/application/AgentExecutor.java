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
import java.util.concurrent.atomic.AtomicReference;

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
        } catch (CancellationException ex) {
            log.warn("session stopped: cancelled");
            throw ex;
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
            cancellationGuard(state.context().cancellation(), turn);
            state.budget().ensureInputTokensWithinBudget();
            state.budget().reserveTurn();
            turn++;
            MDC.put("turn", String.valueOf(turn));
            log.info("turn {} started: messageCount={}", turn, conversation.messages().size());
            AiMessage aiMessage = executeTurn(
                    conversation, state.context().cancellation(), listener, state.budget(), systemPrompt);
            state.remember(aiMessage);
            appendMessage(conversation, aiMessage, "assistant message");
            if (!aiMessage.hasToolUseRequests()) {
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

    private AiMessage executeTurn(Conversation conversation, CancellationToken cancel,
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
        AtomicReference<AiMessage> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Integer> partialFragments = new AtomicReference<>(0);
        AtomicReference<Integer> partialChars = new AtomicReference<>(0);
        llm.streamChat(request, new StreamHandler() {
            @Override public void onPartialText(String delta) {
                cancellationGuard(cancel, currentTurn());
                partialFragments.updateAndGet(value -> value + 1);
                partialChars.updateAndGet(value -> value + delta.length());
                log.debug("llm partial text received: fragment={}, chars={}, totalChars={}",
                        partialFragments.get(), delta.length(), partialChars.get());
                listener.onAssistantTextDelta(delta);
            }
            @Override public void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
                budgetGuard.recordUsage(inputTokens, outputTokens, cacheReadInputTokens);
                log.info("llm usage: inputTokens={}, outputTokens={}, cacheReadInputTokens={}",
                        inputTokens, outputTokens, cacheReadInputTokens);
                listener.onUsage(inputTokens, outputTokens, cacheReadInputTokens);
            }
            @Override public void onComplete(AiMessage message) {
                log.info("llm completed: toolUseCount={}", message.toolUseRequests().size());
                completed.set(message);
            }
            @Override public void onError(Throwable error) {
                log.error("llm stream failed", error);
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            throw new IllegalStateException("LLM stream failed: " + failure.get().getMessage(), failure.get());
        }
        AiMessage result = completed.get();
        if (result == null) {
            throw new IllegalStateException("LLM stream completed without an AiMessage");
        }
        log.info("llm request finished: durationMs={}", elapsedMs(startNs));
        return result;
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

    private static void cancellationGuard(CancellationToken cancel, int turn) {
        try {
            cancel.throwIfCancelled();
        } catch (CancellationException ex) {
            log.warn("cancellation detected: turn={}", turn);
            throw ex;
        }
    }

    private static int currentTurn() {
        String turn = MDC.get("turn");
        return turn == null || turn.isBlank() ? 0 : Integer.parseInt(turn);
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
            AgentBudgetGuard budget = new AgentBudgetGuard(context.budget());
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

        private AgentRunResult finish(
                StopReason reason, Optional<Map<String, Object>> structuredOutput) {
            return new AgentRunResult(context.runId(), reason, lastMessage,
                    structuredOutput, budget.usage(), budget.consumption());
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

}
