package com.anthropic.agentkit.domain.task;

/** Stable metadata keys emitted by background task tools. */
public final class TaskOutputMetadata {

    public static final String TASK_ID_KEY = "agentkit.task.id";
    public static final String TASK_STATE_KEY = "agentkit.task.state";
    public static final String NEXT_CURSOR_KEY = "agentkit.task.next_cursor";

    private TaskOutputMetadata() { }
}
