package com.anthropic.agentkit.application.interception;

/** Stable names used to classify interceptor failures. */
public enum AgentInterceptorHook {
    BEFORE_LLM_CALL("beforeLlmCall"),
    AFTER_LLM_CALL("afterLlmCall"),
    BEFORE_TOOL_DISPATCH("beforeToolDispatch"),
    AFTER_TOOL_SETTLED("afterToolSettled"),
    BEFORE_COMPACTION("beforeCompaction"),
    AFTER_COMPACTION("afterCompaction"),
    BEFORE_RUN_STOP("beforeRunStop"),
    SUB_AGENT_SPAWNED("onSubAgentSpawned"),
    SUB_AGENT_STOPPED("onSubAgentStopped");

    private final String methodName;

    AgentInterceptorHook(String methodName) {
        this.methodName = methodName;
    }

    public String methodName() {
        return methodName;
    }
}
