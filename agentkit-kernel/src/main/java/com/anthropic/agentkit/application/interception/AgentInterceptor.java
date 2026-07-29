package com.anthropic.agentkit.application.interception;

/** Typed in-process extension points around one agent run's lifecycle. */
public interface AgentInterceptor {

    default LlmCallDecision beforeLlmCall(LlmCallContext context) {
        return LlmCallDecision.continueCall();
    }

    default void afterLlmCall(LlmCallCompleted event) {
    }

    default ToolDispatchDecision beforeToolDispatch(ToolDispatchContext context) {
        return ToolDispatchDecision.continueDispatch();
    }

    default void afterToolSettled(ToolSettled event) {
    }

    default CompactionDecision beforeCompaction(CompactionContext context) {
        return CompactionDecision.continueCompaction();
    }

    default void afterCompaction(CompactionCompleted event) {
    }

    default RunStopDecision beforeRunStop(RunStopContext context) {
        return RunStopDecision.continueStop();
    }

    default void onSubAgentSpawned(SubAgentLifecycleEvent event) {
    }

    default void onSubAgentStopped(SubAgentLifecycleEvent event) {
    }
}
