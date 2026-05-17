package com.anthropic.cclc.application;

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
    private final ParallelToolDispatcher dispatcher;

    public AgentExecutor(LlmClient llm, ToolRegistry tools) {
        this(llm, tools, allowAllPermissions(), ExecutionContext.at(currentDirectory()));
    }

    public AgentExecutor(LlmClient llm, ToolRegistry tools, PermissionService permissions) {
        this(llm, tools, permissions, ExecutionContext.at(currentDirectory()));
    }

    public AgentExecutor(LlmClient llm, ToolRegistry tools,
                         PermissionService permissions, ExecutionContext executionContext) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(executionContext, "executionContext");
        this.dispatcher = new ParallelToolDispatcher(tools, executionContext, permissions);
    }

    public CompletableFuture<AiMessage> run(Conversation conversation, CancellationToken cancel) {
        return run(conversation, cancel, NO_OP_HANDLER);
    }

    public CompletableFuture<AiMessage> run(Conversation conversation,
                                            CancellationToken cancel,
                                            StreamHandler streamHandler) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(streamHandler, "streamHandler");
        return CompletableFuture.supplyAsync(() -> loop(conversation, cancel, streamHandler));
    }

    private AiMessage loop(Conversation conversation, CancellationToken cancel, StreamHandler stream) {
        while (true) {
            cancellationGuard(cancel);
            AiMessage aiMessage = executeTurn(conversation, cancel, stream);
            conversation.append(aiMessage);
            if (!aiMessage.hasToolUseRequests()) {
                return aiMessage;
            }
            for (ToolResultMessage result : dispatcher.dispatch(aiMessage)) {
                conversation.append(result);
            }
        }
    }

    private AiMessage executeTurn(Conversation conversation, CancellationToken cancel, StreamHandler stream) {
        ChatRequest.Builder builder = ChatRequest.builder().messages(conversation.messages());
        tools.specs().forEach(builder::tool);
        ChatRequest request = builder.build();
        AtomicReference<AiMessage> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        llm.streamChat(request, new StreamHandler() {
            @Override public void onPartialText(String delta) {
                cancellationGuard(cancel);
                stream.onPartialText(delta);
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

    private static final StreamHandler NO_OP_HANDLER = new StreamHandler() {
        @Override public void onPartialText(String delta) {}
    };

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
