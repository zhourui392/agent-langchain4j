package com.anthropic.agentkit.application.interception;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Immutable declaration-ordered interceptor chain. */
public final class AgentInterceptors {

    private static final Logger log = LoggerFactory.getLogger(AgentInterceptors.class);
    private static final AgentInterceptors NONE = new AgentInterceptors(List.of());

    private final List<AgentInterceptor> delegates;

    private AgentInterceptors(List<AgentInterceptor> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    public static AgentInterceptors none() {
        return NONE;
    }

    public static AgentInterceptors ordered(AgentInterceptor... delegates) {
        Objects.requireNonNull(delegates, "delegates");
        return ordered(Arrays.asList(delegates));
    }

    public static AgentInterceptors ordered(List<? extends AgentInterceptor> delegates) {
        Objects.requireNonNull(delegates, "delegates");
        if (delegates.isEmpty()) {
            return NONE;
        }
        return new AgentInterceptors(List.copyOf(delegates));
    }

    public LlmCallDecision beforeLlmCall(LlmCallContext initial) {
        LlmCallContext current = Objects.requireNonNull(initial, "initial");
        boolean replaced = false;
        for (int index = 0; index < delegates.size(); index++) {
            AgentInterceptor interceptor = delegates.get(index);
            LlmCallContext intercepted = current;
            LlmCallDecision decision = invokeBlocking(
                    AgentInterceptorHook.BEFORE_LLM_CALL, index, interceptor,
                    () -> interceptor.beforeLlmCall(intercepted));
            if (decision instanceof LlmCallDecision.Deny) {
                return decision;
            }
            if (decision instanceof LlmCallDecision.ReplaceContext replacement) {
                current = current.withMessages(replacement.messages());
                replaced = true;
            }
        }
        return replaced ? LlmCallDecision.replaceContext(current.request().messages())
                : LlmCallDecision.continueCall();
    }

    public ToolDispatchDecision beforeToolDispatch(ToolDispatchContext context) {
        Objects.requireNonNull(context, "context");
        for (int index = 0; index < delegates.size(); index++) {
            AgentInterceptor interceptor = delegates.get(index);
            ToolDispatchDecision decision = invokeBlocking(
                    AgentInterceptorHook.BEFORE_TOOL_DISPATCH, index, interceptor,
                    () -> interceptor.beforeToolDispatch(context));
            if (decision instanceof ToolDispatchDecision.Deny) {
                return decision;
            }
        }
        return ToolDispatchDecision.continueDispatch();
    }

    public CompactionDecision beforeCompaction(CompactionContext context) {
        Objects.requireNonNull(context, "context");
        for (int index = 0; index < delegates.size(); index++) {
            AgentInterceptor interceptor = delegates.get(index);
            CompactionDecision decision = invokeBlocking(
                    AgentInterceptorHook.BEFORE_COMPACTION, index, interceptor,
                    () -> interceptor.beforeCompaction(context));
            if (decision instanceof CompactionDecision.Deny) {
                return decision;
            }
        }
        return CompactionDecision.continueCompaction();
    }

    public RunStopDecision beforeRunStop(RunStopContext context) {
        Objects.requireNonNull(context, "context");
        for (int index = 0; index < delegates.size(); index++) {
            AgentInterceptor interceptor = delegates.get(index);
            RunStopDecision decision = invokeBlocking(
                    AgentInterceptorHook.BEFORE_RUN_STOP, index, interceptor,
                    () -> interceptor.beforeRunStop(context));
            if (decision instanceof RunStopDecision.Deny) {
                return decision;
            }
        }
        return RunStopDecision.continueStop();
    }

    public void afterLlmCall(LlmCallCompleted event) {
        observe(AgentInterceptorHook.AFTER_LLM_CALL,
                interceptor -> interceptor.afterLlmCall(event));
    }

    public void afterToolSettled(ToolSettled event) {
        observe(AgentInterceptorHook.AFTER_TOOL_SETTLED,
                interceptor -> interceptor.afterToolSettled(event));
    }

    public void afterCompaction(CompactionCompleted event) {
        observe(AgentInterceptorHook.AFTER_COMPACTION,
                interceptor -> interceptor.afterCompaction(event));
    }

    public void onSubAgentSpawned(SubAgentLifecycleEvent event) {
        observe(AgentInterceptorHook.SUB_AGENT_SPAWNED,
                interceptor -> interceptor.onSubAgentSpawned(event));
    }

    public void onSubAgentStopped(SubAgentLifecycleEvent event) {
        observe(AgentInterceptorHook.SUB_AGENT_STOPPED,
                interceptor -> interceptor.onSubAgentStopped(event));
    }

    private <T> T invokeBlocking(
            AgentInterceptorHook hook, int index, AgentInterceptor interceptor,
            Supplier<T> callback) {
        try {
            return Objects.requireNonNull(callback.get(), "interceptor decision");
        } catch (RuntimeException failure) {
            throw new AgentInterceptorException(hook, index, interceptor, failure);
        }
    }

    private void observe(
            AgentInterceptorHook hook, Consumer<AgentInterceptor> callback) {
        for (int index = 0; index < delegates.size(); index++) {
            AgentInterceptor interceptor = delegates.get(index);
            try {
                callback.accept(interceptor);
            } catch (RuntimeException failure) {
                log.warn("agent interceptor observer failed: hook={}, index={}, type={}",
                        hook.methodName(), index, interceptor.getClass().getName(), failure);
            }
        }
    }
}
