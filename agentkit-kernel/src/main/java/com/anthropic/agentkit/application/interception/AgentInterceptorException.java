package com.anthropic.agentkit.application.interception;

import java.util.Objects;

/** Explicit failure of a blocking interceptor callback. */
public final class AgentInterceptorException extends RuntimeException {

    private final AgentInterceptorHook hook;
    private final int interceptorIndex;
    private final String interceptorType;

    AgentInterceptorException(
            AgentInterceptorHook hook, int interceptorIndex,
            AgentInterceptor interceptor, RuntimeException cause) {
        super(failureMessage(hook, interceptorIndex, interceptor, cause), cause);
        this.hook = Objects.requireNonNull(hook, "hook");
        this.interceptorIndex = interceptorIndex;
        this.interceptorType = interceptor.getClass().getName();
    }

    public AgentInterceptorHook hook() {
        return hook;
    }

    public int interceptorIndex() {
        return interceptorIndex;
    }

    public String interceptorType() {
        return interceptorType;
    }

    private static String failureMessage(
            AgentInterceptorHook hook, int index,
            AgentInterceptor interceptor, RuntimeException cause) {
        String detail = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName() : cause.getMessage();
        return "interceptor[" + index + "] " + interceptor.getClass().getName()
                + " failed during " + hook.methodName() + ": " + detail;
    }
}
