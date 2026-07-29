package com.anthropic.agentkit.infrastructure.task;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.task.ArtifactLimitExceededException;
import com.anthropic.agentkit.domain.task.ArtifactReference;
import com.anthropic.agentkit.domain.task.TaskScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileArtifactStoreTest {

    private static final Instant START = Instant.parse("2026-07-29T00:00:00Z");

    @TempDir Path root;

    @Test
    void artifactIsReadableOnlyWithinItsOwningScope() {
        FileArtifactStore store = store(START, 1_024);
        TaskScope owner = scope("run-a", "workspace-a");
        ArtifactReference reference = store.write(owner, "complete output");

        assertThat(store.read(owner, reference)).contains("complete output");
        assertThat(store.read(scope("run-a", "workspace-b"), reference)).isEmpty();
        assertThat(store.read(scope("run-b", "workspace-a"), reference)).isEmpty();
    }

    @Test
    void expiredArtifactIsUnavailableAndCannotEscapeWorkspacePolicy() {
        TaskScope owner = scope("run-a", "workspace-a");
        ArtifactReference reference = store(START, 1_024).write(owner, "complete output");
        FileArtifactStore expired = store(START.plus(Duration.ofMinutes(6)), 1_024);

        assertThat(expired.read(owner, reference)).isEmpty();
        assertThat(expired.read(scope("run-a", "workspace-b"), reference)).isEmpty();
    }

    @Test
    void artifactSizeLimitIsEnforcedBeforeWriting() {
        FileArtifactStore store = store(START, 16);

        assertThatThrownBy(() -> store.write(
                scope("run-a", "workspace-a"), "x".repeat(17)))
                .isInstanceOf(ArtifactLimitExceededException.class);
        assertThat(root).isEmptyDirectory();
    }

    private FileArtifactStore store(Instant now, int maxCharacters) {
        return new FileArtifactStore(root, maxCharacters, Duration.ofMinutes(5),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static TaskScope scope(String runId, String workspaceId) {
        return new TaskScope(RunId.of(runId), WorkspaceId.of(workspaceId));
    }
}
