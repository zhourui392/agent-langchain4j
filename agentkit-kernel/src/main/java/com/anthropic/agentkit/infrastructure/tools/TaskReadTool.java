package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.application.task.BackgroundTaskService;
import com.anthropic.agentkit.domain.task.OutputChunk;
import com.anthropic.agentkit.domain.task.OutputCursor;
import com.anthropic.agentkit.domain.task.TaskId;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;

import java.util.Objects;

/** Reads task output incrementally from a caller-supplied cursor. */
public final class TaskReadTool implements Tool {

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "task_id":{"type":"string"},\
            "cursor":{"type":"integer","description":"previous next cursor; default 0"}\
            },"required":["task_id"]}""";

    private final BackgroundTaskService tasks;

    public TaskReadTool(BackgroundTaskService tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override public String name() { return "TaskRead"; }
    @Override public String description() { return "Read new output from a background task"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public ToolResult execute(ToolArguments arguments, ExecutionContext context) {
        TaskId id = TaskId.of(arguments.getString("task_id"));
        OutputCursor cursor = new OutputCursor(cursor(arguments));
        OutputChunk output = tasks.read(id, cursor, context);
        return BackgroundTaskToolJson.output(id, output);
    }

    private static long cursor(ToolArguments arguments) {
        Object value = arguments.values().get("cursor");
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }
}
