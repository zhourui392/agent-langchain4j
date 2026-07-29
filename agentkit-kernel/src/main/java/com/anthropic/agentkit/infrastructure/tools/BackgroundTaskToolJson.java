package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.task.OutputChunk;
import com.anthropic.agentkit.domain.task.TaskId;
import com.anthropic.agentkit.domain.task.TaskOutputMetadata;
import com.anthropic.agentkit.domain.task.TaskSnapshot;
import com.anthropic.agentkit.domain.task.TaskState;
import com.anthropic.agentkit.domain.task.TaskStopResult;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolOutputMetadata;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable JSON and metadata projection shared by background task tools. */
final class BackgroundTaskToolJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BackgroundTaskToolJson() { }

    static ToolResult started(TaskSnapshot snapshot) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("task_id", snapshot.id().value());
        value.put("state", snapshot.state().name());
        return result(value, snapshot.id(), snapshot.state());
    }

    static ToolResult status(TaskSnapshot snapshot) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("task_id", snapshot.id().value());
        value.put("state", snapshot.state().name());
        value.put("output_characters", snapshot.outputCharacters());
        value.put("preview", snapshot.preview());
        snapshot.artifact().ifPresent(reference -> value.put("artifact", reference.uri()));
        Map<String, String> metadata = metadata(snapshot.id(), snapshot.state());
        snapshot.artifact().ifPresent(reference -> metadata.put(
                ToolOutputMetadata.ARTIFACT_KEY, reference.uri().toString()));
        return ToolResult.of(ToolResultStatus.SUCCESS, encode(value), metadata);
    }

    static ToolResult output(TaskId id, OutputChunk chunk) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("task_id", id.value());
        value.put("state", chunk.state().name());
        value.put("content", chunk.content());
        value.put("cursor", chunk.next().position());
        Map<String, String> metadata = metadata(id, chunk.state());
        metadata.put(TaskOutputMetadata.NEXT_CURSOR_KEY,
                String.valueOf(chunk.next().position()));
        return ToolResult.of(ToolResultStatus.SUCCESS, encode(value), metadata);
    }

    static ToolResult stopped(TaskStopResult stopped) {
        TaskSnapshot snapshot = stopped.snapshot();
        Map<String, Object> value = Map.of(
                "task_id", snapshot.id().value(),
                "state", snapshot.state().name(),
                "changed", stopped.changed());
        return result(value, snapshot.id(), snapshot.state());
    }

    private static ToolResult result(
            Map<String, Object> value, TaskId id, TaskState state) {
        return ToolResult.of(
                ToolResultStatus.SUCCESS, encode(value), metadata(id, state));
    }

    private static Map<String, String> metadata(TaskId id, TaskState state) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(TaskOutputMetadata.TASK_ID_KEY, id.value());
        metadata.put(TaskOutputMetadata.TASK_STATE_KEY, state.name());
        return metadata;
    }

    private static String encode(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("failed to encode background task result", failure);
        }
    }
}
