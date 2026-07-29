package com.anthropic.agentkit.application.task;

import com.anthropic.agentkit.domain.port.ArtifactStore;
import com.anthropic.agentkit.domain.port.ArtifactStoreException;
import com.anthropic.agentkit.domain.task.ArtifactLimitExceededException;
import com.anthropic.agentkit.domain.task.ArtifactReference;
import com.anthropic.agentkit.domain.task.OutputChunk;
import com.anthropic.agentkit.domain.task.TaskScope;
import com.anthropic.agentkit.domain.task.TaskState;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/** Produces bounded task snapshots from live output and terminal tool results. */
final class BackgroundTaskOutputProjector {

    private static final Logger log = LoggerFactory.getLogger(
            BackgroundTaskOutputProjector.class);

    private final ArtifactStore artifacts;
    private final ArtifactContentPolicy contentPolicy;
    private final BackgroundTaskPolicy taskPolicy;

    BackgroundTaskOutputProjector(
            ArtifactStore artifacts,
            ArtifactContentPolicy contentPolicy,
            BackgroundTaskPolicy taskPolicy) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.contentPolicy = Objects.requireNonNull(contentPolicy, "contentPolicy");
        this.taskPolicy = Objects.requireNonNull(taskPolicy, "taskPolicy");
    }

    TaskOutputProjection active(OutputChunk output, ExecutionContext context) {
        Objects.requireNonNull(output, "output");
        Optional<String> governed = govern(output.content(), context);
        if (governed.isEmpty()) {
            return unavailable(output.state(), output.next().position());
        }
        return new TaskOutputProjection(
                output.state(), taskPolicy.preview(
                        governed.orElseThrow(), Optional.empty()),
                output.next().position(), Optional.empty());
    }

    TaskOutputProjection completed(
            ToolResult result, Throwable failure,
            ExecutionContext context, TaskScope scope) {
        if (failure != null || result == null) {
            return failedCompletion(failure);
        }
        Optional<String> governed = govern(result.content(), context);
        if (governed.isEmpty()) {
            return unavailable(TaskState.FAILED, result.content().length());
        }
        String content = governed.orElseThrow();
        Optional<ArtifactReference> artifact = storeLargeOutput(scope, content);
        return new TaskOutputProjection(
                TaskState.from(result.status()), taskPolicy.preview(content, artifact),
                result.content().length(), artifact);
    }

    private Optional<String> govern(String content, ExecutionContext context) {
        try {
            return Optional.of(Objects.requireNonNull(
                    contentPolicy.govern(content, context),
                    "artifact content policy result"));
        } catch (RuntimeException policyFailure) {
            log.warn("background output governance failed: failure={}",
                    policyFailure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<ArtifactReference> storeLargeOutput(
            TaskScope scope, String content) {
        if (content.length() <= taskPolicy.previewCharacters()) {
            return Optional.empty();
        }
        try {
            return Optional.of(artifacts.write(scope, content));
        } catch (ArtifactStoreException | ArtifactLimitExceededException failure) {
            log.warn("background artifact unavailable: failure={}",
                    failure.getClass().getSimpleName());
            return Optional.empty();
        } catch (RuntimeException unexpectedFailure) {
            log.error("background artifact contract failed: failure={}",
                    unexpectedFailure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static TaskOutputProjection failedCompletion(Throwable failure) {
        String type = failure == null ? "missing result" : failure.getClass().getSimpleName();
        log.warn("background completion failed: failure={}", type);
        String preview = "background task failed before output projection";
        return new TaskOutputProjection(
                TaskState.FAILED, preview, preview.length(), Optional.empty());
    }

    private static TaskOutputProjection unavailable(
            TaskState state, long outputCharacters) {
        return new TaskOutputProjection(
                state, "background task output unavailable",
                outputCharacters, Optional.empty());
    }
}
