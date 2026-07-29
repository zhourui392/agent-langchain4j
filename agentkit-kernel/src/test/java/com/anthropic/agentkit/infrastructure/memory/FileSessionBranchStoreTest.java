package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.session.RunEventPointer;
import com.anthropic.agentkit.domain.session.SessionBranch;
import com.anthropic.agentkit.domain.session.SessionBranchEvent;
import com.anthropic.agentkit.domain.session.SessionBranchId;
import com.anthropic.agentkit.domain.session.SessionBranchScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSessionBranchStoreTest {

    @Test
    void branchCreationRoundTripsAndCannotBeRewritten(@TempDir Path directory)
            throws IOException {
        FileSessionBranchStore store = new FileSessionBranchStore(directory);
        SessionBranch branch = SessionBranch.root(
                SessionBranchId.of("branch-1"),
                new SessionBranchScope(
                        SessionId.of("session-1"), WorkspaceId.of("workspace-1")),
                new RunEventPointer(RunId.of("run-1"), 7));
        SessionBranchEvent event = new SessionBranchEvent.BranchCreated(
                SessionBranchEvent.CURRENT_SCHEMA_VERSION, 1,
                Instant.parse("2026-07-29T17:00:00Z"), branch);
        store.append(event);
        byte[] original = Files.readAllBytes(store.pathFor(branch.id()));

        assertThat(store.load(branch.id())).containsExactly(event);
        assertThatThrownBy(() -> store.append(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable");
        assertThat(Files.readAllBytes(store.pathFor(branch.id())))
                .isEqualTo(original);
    }
}
