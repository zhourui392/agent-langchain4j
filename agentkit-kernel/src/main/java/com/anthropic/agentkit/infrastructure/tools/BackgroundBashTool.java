package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.application.task.BackgroundTaskService;
import com.anthropic.agentkit.domain.task.BackgroundTaskRequest;
import com.anthropic.agentkit.domain.task.TaskSnapshot;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.ShellSelector;

import java.time.Duration;
import java.util.Objects;

/** Starts a shell command and immediately settles with a scoped task id. */
public final class BackgroundBashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "command":{"type":"string","description":"shell command"},\
            "timeout":{"type":"integer","description":"task timeout in ms"}\
            },"required":["command"]}""";

    private final BackgroundTaskService tasks;

    public BackgroundBashTool(BackgroundTaskService tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override public String name() { return "BashBackground"; }

    @Override public String description() {
        return "Start a shell command in the background and return its task id";
    }

    @Override public String inputSchema() { return INPUT_SCHEMA; }

    @Override public boolean isReadOnly() { return false; }

    @Override
    public ToolResult execute(ToolArguments arguments, ExecutionContext context) {
        String command = arguments.getString("command");
        int timeoutMs = arguments.getInt("timeout", DEFAULT_TIMEOUT_MS);
        BackgroundTaskRequest request = new BackgroundTaskRequest(
                "background shell command", ShellSelector.commandFor(command),
                Duration.ofMillis(timeoutMs));
        TaskSnapshot snapshot = tasks.start(request, context);
        return BackgroundTaskToolJson.started(snapshot);
    }
}
