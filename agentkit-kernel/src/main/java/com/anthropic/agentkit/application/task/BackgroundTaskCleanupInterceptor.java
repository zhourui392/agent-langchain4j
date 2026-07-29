package com.anthropic.agentkit.application.task;

import com.anthropic.agentkit.application.interception.AgentInterceptor;
import com.anthropic.agentkit.application.interception.RunStopContext;
import com.anthropic.agentkit.application.interception.RunStopDecision;

import java.util.Objects;

/** Cancels active tasks owned by a run immediately before that run stops. */
public final class BackgroundTaskCleanupInterceptor implements AgentInterceptor {

    private final BackgroundTaskService tasks;

    public BackgroundTaskCleanupInterceptor(BackgroundTaskService tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public RunStopDecision beforeRunStop(RunStopContext context) {
        tasks.close(context.runContext().executionContext());
        return RunStopDecision.continueStop();
    }
}
