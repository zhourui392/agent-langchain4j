package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.application.task.BackgroundTaskService;
import com.anthropic.agentkit.domain.task.TaskId;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;

import java.util.Objects;

/** Stops a scoped background task and its process tree. */
public final class TaskStopTool implements Tool {

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "task_id":{"type":"string"}\
            },"required":["task_id"]}""";

    private final BackgroundTaskService tasks;

    public TaskStopTool(BackgroundTaskService tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override public String name() { return "TaskStop"; }
    @Override public String description() { return "Stop a background task"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public ToolResult execute(ToolArguments arguments, ExecutionContext context) {
        TaskId id = TaskId.of(arguments.getString("task_id"));
        return BackgroundTaskToolJson.stopped(tasks.stop(id, context));
    }
}
