package com.anthropic.agentkit.domain.task;

import com.anthropic.agentkit.domain.tool.ToolResult;

import java.util.concurrent.CompletionStage;

/** Aggregate boundary for one running or completed background task. */
public interface TaskHandle {

    TaskId id();

    TaskScope scope();

    TaskState state();

    OutputChunk readSince(OutputCursor cursor);

    CompletionStage<ToolResult> completion();

    boolean cancel();
}
