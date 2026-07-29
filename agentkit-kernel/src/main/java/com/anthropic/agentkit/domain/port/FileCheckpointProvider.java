package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.checkpoint.CheckpointId;
import com.anthropic.agentkit.domain.checkpoint.CheckpointOwner;
import com.anthropic.agentkit.domain.checkpoint.FileCheckpointScope;

import java.nio.file.Path;
import java.util.Optional;

/** Captures and restores only file changes explicitly managed by kernel tools. */
public interface FileCheckpointProvider {

    Optional<CheckpointId> capture(FileCheckpointScope scope, Path file);

    void restore(CheckpointOwner owner, CheckpointId checkpointId);

    static FileCheckpointProvider none() {
        return NoOpFileCheckpointProvider.INSTANCE;
    }

    enum NoOpFileCheckpointProvider implements FileCheckpointProvider {
        INSTANCE;

        @Override
        public Optional<CheckpointId> capture(FileCheckpointScope scope, Path file) {
            return Optional.empty();
        }

        @Override
        public void restore(CheckpointOwner owner, CheckpointId checkpointId) {
            throw new IllegalStateException("file checkpoint provider is disabled");
        }
    }
}
