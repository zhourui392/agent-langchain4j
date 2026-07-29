package com.anthropic.agentkit.infrastructure.checkpoint;

import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.checkpoint.CheckpointId;
import com.anthropic.agentkit.domain.checkpoint.CheckpointOwner;
import com.anthropic.agentkit.domain.checkpoint.FileCheckpointException;
import com.anthropic.agentkit.domain.checkpoint.FileCheckpointScope;
import com.anthropic.agentkit.domain.conversation.SessionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemCheckpointProviderTest {

    private static final CheckpointOwner OWNER = new CheckpointOwner(
            SessionId.of("checkpoint-session"), WorkspaceId.of("checkpoint-workspace"));

    @TempDir Path tempDir;

    @Test
    void restoresExistingFileContentAndModifiedTime() throws IOException {
        Path workspace = workspace();
        Path file = workspace.resolve("note.txt");
        Files.writeString(file, "before");
        var originalTime = Files.getLastModifiedTime(file);
        FileSystemCheckpointProvider provider = provider();
        CheckpointId checkpoint = provider.capture(scope(workspace), file).orElseThrow();

        Files.writeString(file, "after");
        provider.restore(OWNER, checkpoint);

        assertThat(Files.readString(file)).isEqualTo("before");
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(originalTime);
    }

    @Test
    void restoringCheckpointForNewFileDeletesCreatedFile() throws IOException {
        Path workspace = workspace();
        Path file = workspace.resolve("created.txt");
        FileSystemCheckpointProvider provider = provider();
        CheckpointId checkpoint = provider.capture(scope(workspace), file).orElseThrow();
        Files.writeString(file, "created later");

        provider.restore(OWNER, checkpoint);

        assertThat(file).doesNotExist();
        assertThat(provider.pathFor(checkpoint)).exists();
    }

    @Test
    void wrongOwnerCannotRestoreOrConsumeCheckpoint() throws IOException {
        Path workspace = workspace();
        Path file = workspace.resolve("note.txt");
        Files.writeString(file, "before");
        FileSystemCheckpointProvider provider = provider();
        CheckpointId checkpoint = provider.capture(scope(workspace), file).orElseThrow();
        Files.writeString(file, "after");
        CheckpointOwner intruder = new CheckpointOwner(
                OWNER.sessionId(), WorkspaceId.of("other-workspace"));

        assertThatThrownBy(() -> provider.restore(intruder, checkpoint))
                .isInstanceOf(FileCheckpointException.class)
                .hasMessage("file checkpoint is unavailable");
        assertThat(Files.readString(file)).isEqualTo("after");

        provider.restore(OWNER, checkpoint);
        assertThat(Files.readString(file)).isEqualTo("before");
    }

    @Test
    void checkpointIdCannotEscapeStorageDirectory() {
        FileSystemCheckpointProvider provider = provider();
        Path path = provider.pathFor(CheckpointId.of("../../outside"));

        assertThat(path.normalize().startsWith(
                tempDir.resolve("checkpoints").normalize())).isTrue();
    }

    private Path workspace() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        return workspace;
    }

    private FileSystemCheckpointProvider provider() {
        return new FileSystemCheckpointProvider(tempDir.resolve("checkpoints"));
    }

    private static FileCheckpointScope scope(Path workspace) {
        return new FileCheckpointScope(OWNER, workspace);
    }
}
