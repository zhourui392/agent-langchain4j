package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentBudgetExceededException;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmClient.StreamHandler;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolRegistry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
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
    private final ExecutionContext defaultExecutionContext;
    private final AgentBudget defaultBudget;

    public AgentExecutor(LlmClient llm, ToolRegistry tools) {
        this(llm, tools, allowAllPermissions(), ExecutionContext.at(currentDirectory()));
    }

    public AgentExecutor(LlmClient llm, ToolRegistry tools, PermissionService permissions) {
        this(llm, tools, permissions, ExecutionContext.at(currentDirectory()));
    }

    public AgentExecutor(LlmClient llm, ToolRegistry tools,
                         PermissionService permissions, ExecutionContext executionContext) {
        this(llm, tools, permissions, executionContext, AgentBudget.unlimited());
    }

    public AgentExecutor(LlmClient llm, ToolRegistry tools,
                         PermissionService permissions, AgentBudget budget) {
        this(llm, tools, permissions, ExecutionContext.at(currentDirectory()), budget);
    }

    public AgentExecutor(LlmClient llm, ToolRegistry tools,
                         PermissionService permissions, ExecutionContext executionContext,
                         AgentBudget budget) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.defaultExecutionContext = Objects.requireNonNull(executionContext, "executionContext");
        this.defaultBudget = Objects.requireNonNull(budget, "budget");
    }

    public CompletableFuture<AiMessage> run(Conversation conversation, CancellationToken cancel) {
        return run(conversation, legacyContext(conversation, cancel), AgentEventListener.NO_OP);
    }

    public CompletableFuture<AiMessage> run(Conversation conversation,
                                            CancellationToken cancel,
                                            StreamHandler streamHandler) {
        Objects.requireNonNull(streamHandler, "streamHandler");
        return run(conversation, legacyContext(conversation, cancel), fromStreamHandler(streamHandler));
    }

    public CompletableFuture<AiMessage> run(Conversation conversation,
                                            CancellationToken cancel,
                                            AgentEventListener listener) {
        return run(conversation, legacyContext(conversation, cancel), listener, "");
    }

    public CompletableFuture<AiMessage> run(Conversation conversation,
                                            CancellationToken cancel,
                                            AgentEventListener listener,
                                            String systemPrompt) {
        return run(conversation, legacyContext(conversation, cancel), listener, systemPrompt);
    }

    public CompletableFuture<AiMessage> run(Conversation conversation, AgentRunContext context) {
        return run(conversation, context, AgentEventListener.NO_OP, "");
    }

    public CompletableFuture<AiMessage> run(Conversation conversation, AgentRunContext context,
                                            AgentEventListener listener) {
        return run(conversation, context, listener, "");
    }

    public CompletableFuture<AiMessage> run(Conversation conversation, AgentRunContext context,
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

    private AiMessage loop(Conversation conversation, AgentRunContext context,
                           AgentEventListener listener, String systemPrompt) {
        MDC.put("session", conversation.sessionId().value());
        MDC.put("run", context.runId().value());
        log.info("session started: runId={}, workspaceId={}, initialMessages={}, systemPromptChars={}",
                context.runId(), context.workspaceId(), conversation.messages().size(), systemPrompt.length());
        try {
            return runLoop(conversation, context, listener, systemPrompt);
        } catch (AgentBudgetExceededException ex) {
            log.warn("session stopped: budget exhausted: {}", ex.getMessage());
            throw ex;
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

    private AiMessage runLoop(Conversation conversation, AgentRunContext context,
                              AgentEventListener listener, String systemPrompt) {
        AgentBudgetGuard budgetGuard = new AgentBudgetGuard(context.budget());
        ParallelToolDispatcher dispatcher = new ParallelToolDispatcher(
                tools, context.runId(), context.executionContext(), permissions);
        int turn = 0;
        while (true) {
            cancellationGuard(context.cancellation(), turn);
            budgetGuard.ensureInputTokensWithinBudget();
            budgetGuard.reserveTurn();
            turn++;
            MDC.put("turn", String.valueOf(turn));
            log.info("turn {} started: messageCount={}", turn, conversation.messages().size());
            AiMessage aiMessage = executeTurn(
                    conversation, context.cancellation(), listener, budgetGuard, systemPrompt);
            appendMessage(conversation, aiMessage, "assistant message");
            if (!aiMessage.hasToolUseRequests()) {
                log.info("turn {} completed: stopReason=no_tool_use", turn);
                listener.onTurnComplete(aiMessage);
                return aiMessage;
            }
            budgetGuard.ensureInputTokensWithinBudget();
            budgetGuard.reserveToolCalls(aiMessage.toolUseRequests().size());
            for (ToolResultMessage result : dispatcher.dispatch(aiMessage, listener)) {
                appendMessage(conversation, result, "tool result");
            }
            log.info("turn {} completed: toolResults={}", turn, aiMessage.toolUseRequests().size());
        }
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
                budgetGuard.recordInputTokens(inputTokens);
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

    private static AgentEventListener fromStreamHandler(StreamHandler handler) {
        return new AgentEventListener() {
            @Override public void onAssistantTextDelta(String delta) { handler.onPartialText(delta); }
            @Override public void onTurnComplete(AiMessage finalMessage) { handler.onComplete(finalMessage); }
            @Override public void onError(Throwable error) { handler.onError(error); }
        };
    }

    private AgentRunContext legacyContext(Conversation conversation, CancellationToken cancel) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(cancel, "cancel");
        return AgentRunContext.of(RunId.fresh(), conversation.sessionId(),
                defaultExecutionContext.workspaceId(), defaultExecutionContext.cwd(), cancel, defaultBudget);
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

    private static PermissionService allowAllPermissions() {
        return new PermissionService(
                (invocation, tool, mode) -> Decision.ALLOW,
                (invocation, tool) -> {
                    throw new IllegalStateException("interactive prompter not configured");
                },
                PermissionMode.BYPASS);
    }

    private static Path currentDirectory() {
        return Paths.get(System.getProperty("user.dir", "."));
    }
}
