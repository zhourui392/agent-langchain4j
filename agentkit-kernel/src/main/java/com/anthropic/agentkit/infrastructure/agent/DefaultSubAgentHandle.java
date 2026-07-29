package com.anthropic.agentkit.infrastructure.agent;

import com.anthropic.agentkit.application.AgentEventListener;
import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.application.interception.AgentInterceptors;
import com.anthropic.agentkit.application.interception.SubAgentLifecycleEvent;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentRunState;
import com.anthropic.agentkit.domain.agent.AgentSpec;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.agent.SubAgentExecutionScope;
import com.anthropic.agentkit.domain.agent.SubAgentHandle;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmClientSelector;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Serial child-session lifecycle used by {@link DefaultSubAgentRuntime}. */
final class DefaultSubAgentHandle implements SubAgentHandle {

    private final Object lifecycle = new Object();
    private final AgentSpec spec;
    private final ExecutionContext parent;
    private final Conversation conversation;
    private final ToolRegistry tools;
    private final LlmClientSelector clients;
    private final PermissionService permissions;
    private final SubAgentExecutionScope scope;
    private final AgentInterceptors interceptors;
    private volatile AgentRunState state = AgentRunState.STARTING;
    private volatile RunId childRunId;
    private volatile CompletableFuture<AgentRunResult> currentResult;
    private volatile CancellationToken activeCancellation;
    private boolean closed;

    DefaultSubAgentHandle(
            AgentSpec spec, ExecutionContext parent, Conversation conversation,
            ToolRegistry tools, LlmClientSelector clients,
            PermissionService permissions, SubAgentExecutionScope scope,
            AgentInterceptors interceptors) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.parent = Objects.requireNonNull(parent, "parent");
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
    }

    void start(String task, SubAgentExecutionScope.Lease lease) {
        synchronized (lifecycle) {
            conversation.append(UserMessage.of(task));
            startSegment(lease);
        }
    }

    @Override
    public AgentId id() {
        return spec.id();
    }

    @Override
    public RunId parentRunId() {
        return parent.runId();
    }

    @Override
    public RunId childRunId() {
        return childRunId;
    }

    @Override
    public SessionId sessionId() {
        return conversation.sessionId();
    }

    @Override
    public AgentRunState state() {
        return state;
    }

    @Override
    public CompletionStage<AgentRunResult> result() {
        return currentResult;
    }

    @Override
    public CompletionStage<AgentRunResult> followUp(String message) {
        requireMessage(message);
        synchronized (lifecycle) {
            ensureFollowUpAllowed();
            SubAgentExecutionScope.Lease lease = scope.acquire();
            try {
                conversation.append(UserMessage.of(message));
                return startSegment(lease);
            } catch (RuntimeException failure) {
                lease.close();
                throw failure;
            }
        }
    }

    @Override
    public boolean cancel() {
        synchronized (lifecycle) {
            if (closed) {
                return false;
            }
            closed = true;
            state = AgentRunState.CANCELLED;
            if (activeCancellation != null) {
                activeCancellation.cancel();
            }
            return true;
        }
    }

    private CompletionStage<AgentRunResult> startSegment(
            SubAgentExecutionScope.Lease lease) {
        state = AgentRunState.RUNNING;
        childRunId = RunId.fresh();
        activeCancellation = new CancellationToken();
        AgentRunContext context = childContext();
        interceptors.onSubAgentSpawned(lifecycleEvent(
                AgentRunState.RUNNING, Optional.empty()));
        CancellationToken.Registration propagation = parent.cancellation()
                .onCancel(this::cancelFromParent);
        try {
            CompletableFuture<AgentRunResult> future = executor().run(
                    conversation, context, AgentEventListener.NO_OP, spec.systemPrompt());
            currentResult = future.whenComplete(
                    (result, failure) -> finish(result, failure, propagation, lease));
            return currentResult;
        } catch (RuntimeException failure) {
            propagation.close();
            lease.close();
            state = AgentRunState.FAILED;
            interceptors.onSubAgentStopped(lifecycleEvent(
                    AgentRunState.FAILED, Optional.empty()));
            throw failure;
        }
    }

    private AgentRunContext childContext() {
        return AgentRunContext.childRun(
                parent, conversation.sessionId(), childRunId, activeCancellation,
                spec.budget(), spec.limits(), scope);
    }

    private AgentExecutor executor() {
        LlmClient client = Objects.requireNonNull(
                clients.select(spec.modelTier()), "selected LLM client");
        return new AgentExecutor(client, tools, permissions, interceptors);
    }

    private void cancelFromParent() {
        synchronized (lifecycle) {
            closed = true;
            state = AgentRunState.CANCELLED;
            activeCancellation.cancel();
        }
    }

    private void finish(
            AgentRunResult result, Throwable failure,
            CancellationToken.Registration propagation,
            SubAgentExecutionScope.Lease lease) {
        propagation.close();
        lease.close();
        synchronized (lifecycle) {
            if (closed || result != null && result.stopReason() == StopReason.CANCELLED) {
                closed = true;
                state = AgentRunState.CANCELLED;
            } else if (failure != null || !completedNormally(result)) {
                state = AgentRunState.FAILED;
            } else {
                state = AgentRunState.COMPLETED;
            }
        }
        Optional<StopReason> stopReason = result == null
                ? Optional.empty() : Optional.of(result.stopReason());
        interceptors.onSubAgentStopped(lifecycleEvent(state, stopReason));
    }

    private SubAgentLifecycleEvent lifecycleEvent(
            AgentRunState lifecycleState, Optional<StopReason> stopReason) {
        return new SubAgentLifecycleEvent(
                spec.id(), parent.runId(), childRunId, conversation.sessionId(),
                lifecycleState, stopReason);
    }

    private static boolean completedNormally(AgentRunResult result) {
        return result != null && (result.stopReason() == StopReason.MODEL_COMPLETED
                || result.stopReason() == StopReason.TERMINAL_TOOL);
    }

    private void ensureFollowUpAllowed() {
        if (closed || state != AgentRunState.COMPLETED
                || currentResult == null || !currentResult.isDone()) {
            throw new IllegalStateException(
                    "follow-up requires a completed, active child session");
        }
    }

    private static void requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("follow-up message must not be blank");
        }
    }
}
