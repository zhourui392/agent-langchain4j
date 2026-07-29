package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.task.TaskHandle;
import com.anthropic.agentkit.domain.task.TaskLaunchSpec;

/** Starts an explicitly scoped background operation without exposing Process to the domain. */
@FunctionalInterface
public interface BackgroundTaskLauncher {

    TaskHandle launch(TaskLaunchSpec spec);
}
