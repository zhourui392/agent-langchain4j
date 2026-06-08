package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.application.AgentExecutor;
import com.anthropic.cclc.application.InteractivePrompter;
import com.anthropic.cclc.application.PermissionService;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.infrastructure.permission.ReadOnlyPermissionPolicy;

import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Default {@link DiagnoseEngine}: rebuilds a stateless conversation, runs the
 * shared {@code AgentExecutor} under a read-only permission policy, and streams
 * events as Claude {@code stream-json}.
 *
 * <p>{@code runStream} blocks until the turn finishes. The read-only policy is
 * hardcoded — the engine's identity is diagnosis, so it can never be configured
 * to mutate. The interactive prompter is never reached (the policy never asks).
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class DefaultDiagnoseEngine implements DiagnoseEngine {

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_CANCELLED = -1;
    private static final int EXIT_ERROR = 1;

    private static final InteractivePrompter REJECTING_PROMPTER = (invocation, tool) -> {
        throw new IllegalStateException("read-only diagnose engine has no interactive approval");
    };

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final ConversationRebuilder rebuilder = new ConversationRebuilder();
    private final RunningSessions running = new RunningSessions();
    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(DefaultDiagnoseEngine::daemon);

    public DefaultDiagnoseEngine(LlmClient llm, ToolRegistry tools) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    @Override
    public void runStream(RunRequest request, Consumer<String> onChunk, IntConsumer onExit) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onChunk, "onChunk");
        Objects.requireNonNull(onExit, "onExit");
        String sessionId = request.sessionId();
        Conversation conversation = rebuilder.from(
                SessionId.of(sessionId), request.history(), request.userMessage());
        CancellationToken cancel = running.register(sessionId);
        try {
            await(request, conversation, cancel, onChunk);
            onExit.accept(EXIT_SUCCESS);
        } catch (RuntimeException ex) {
            onExit.accept(cancel.isCancelled() ? EXIT_CANCELLED : EXIT_ERROR);
        } finally {
            running.remove(sessionId);
        }
    }

    @Override
    public void stop(String sessionId) {
        running.cancel(sessionId);
    }

    @Override
    public boolean isRunning(String sessionId) {
        return running.isRunning(sessionId);
    }

    private void await(RunRequest request, Conversation conversation,
                       CancellationToken cancel, Consumer<String> onChunk) {
        ExecutionContext context = ExecutionContext.of(Paths.get(request.workingDir()), cancel);
        PermissionService permissions = new PermissionService(
                new ReadOnlyPermissionPolicy(), REJECTING_PROMPTER, PermissionMode.BYPASS);
        AgentExecutor executor = new AgentExecutor(llm, tools, permissions, context);
        ClaudeStreamJsonListener listener = new ClaudeStreamJsonListener(
                request.sessionId(), request.workingDir(), onChunk);
        ScheduledFuture<?> timeout = scheduleTimeout(cancel, request.timeoutSeconds());
        try {
            executor.run(conversation, cancel, listener).join();
        } finally {
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
    }

    private ScheduledFuture<?> scheduleTimeout(CancellationToken cancel, long timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return null;
        }
        return timeoutScheduler.schedule(cancel::cancel, timeoutSeconds, TimeUnit.SECONDS);
    }

    private static Thread daemon(Runnable runnable) {
        Thread thread = new Thread(runnable, "diagnose-timeout");
        thread.setDaemon(true);
        return thread;
    }
}
