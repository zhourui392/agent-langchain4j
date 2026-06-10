package com.anthropic.cclc.application;

import com.anthropic.cclc.domain.agent.AgentBudget;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.ToolResultMessage;
import com.anthropic.cclc.domain.permission.Decision;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.port.ChatRequest;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.port.LlmClient.StreamHandler;
import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.ToolRegistry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentExecutor {

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final AgentBudget budget;
    private final ParallelToolDispatcher dispatcher;

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
        this.budget = Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(executionContext, "executionContext");
        this.dispatcher = new ParallelToolDispatcher(tools, executionContext, permissions);
    }

    public CompletableFuture<AiMessage> run(Conversation conversation, CancellationToken cancel) {
        return run(conversation, cancel, AgentEventListener.NO_OP);
    }

    public CompletableFuture<AiMessage> run(Conversation conversation,
                                            CancellationToken cancel,
                                            StreamHandler streamHandler) {
        Objects.requireNonNull(streamHandler, "streamHandler");
        return run(conversation, cancel, fromStreamHandler(streamHandler));
    }

    public CompletableFuture<AiMessage> run(Conversation conversation,
                                            CancellationToken cancel,
                                            AgentEventListener listener) {
        return run(conversation, cancel, listener, "");
    }

    public CompletableFuture<AiMessage> run(Conversation conversation,
                                            CancellationToken cancel,
                                            AgentEventListener listener,
                                            String systemPrompt) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        return CompletableFuture.supplyAsync(() -> loop(conversation, cancel, listener, systemPrompt));
    }

    private AiMessage loop(Conversation conversation, CancellationToken cancel,
                           AgentEventListener listener, String systemPrompt) {
        try {
            return runLoop(conversation, cancel, listener, systemPrompt);
        } catch (RuntimeException ex) {
            listener.onError(ex);
            throw ex;
        }
    }

    private AiMessage runLoop(Conversation conversation, CancellationToken cancel,
                              AgentEventListener listener, String systemPrompt) {
        AgentBudgetGuard budgetGuard = new AgentBudgetGuard(budget);
        while (true) {
            cancellationGuard(cancel);
            budgetGuard.ensureInputTokensWithinBudget();
            budgetGuard.reserveTurn();
            AiMessage aiMessage = executeTurn(conversation, cancel, listener, budgetGuard, systemPrompt);
            conversation.append(aiMessage);
            if (!aiMessage.hasToolUseRequests()) {
                listener.onTurnComplete(aiMessage);
                return aiMessage;
            }
            budgetGuard.ensureInputTokensWithinBudget();
            budgetGuard.reserveToolCalls(aiMessage.toolUseRequests().size());
            for (ToolResultMessage result : dispatcher.dispatch(aiMessage, listener)) {
                conversation.append(result);
            }
        }
    }

    private AiMessage executeTurn(Conversation conversation, CancellationToken cancel,
                                  AgentEventListener listener, AgentBudgetGuard budgetGuard,
                                  String systemPrompt) {
        listener.onLlmRequestStart();
        ChatRequest.Builder builder = ChatRequest.builder()
                .systemPrompt(systemPrompt)
                .messages(conversation.messages());
        tools.specs().forEach(builder::tool);
        ChatRequest request = builder.build();
        AtomicReference<AiMessage> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        llm.streamChat(request, new StreamHandler() {
            @Override public void onPartialText(String delta) {
                cancellationGuard(cancel);
                listener.onAssistantTextDelta(delta);
            }
            @Override public void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
                budgetGuard.recordInputTokens(inputTokens);
                listener.onUsage(inputTokens, outputTokens, cacheReadInputTokens);
            }
            @Override public void onComplete(AiMessage message) { completed.set(message); }
            @Override public void onError(Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) {
            throw new IllegalStateException("LLM stream failed: " + failure.get().getMessage(), failure.get());
        }
        AiMessage result = completed.get();
        if (result == null) {
            throw new IllegalStateException("LLM stream completed without an AiMessage");
        }
        return result;
    }

    private static AgentEventListener fromStreamHandler(StreamHandler handler) {
        return new AgentEventListener() {
            @Override public void onAssistantTextDelta(String delta) { handler.onPartialText(delta); }
            @Override public void onTurnComplete(AiMessage finalMessage) { handler.onComplete(finalMessage); }
            @Override public void onError(Throwable error) { handler.onError(error); }
        };
    }

    private static void cancellationGuard(CancellationToken cancel) {
        cancel.throwIfCancelled();
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
