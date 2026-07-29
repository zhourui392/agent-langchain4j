package com.anthropic.agentkit.infrastructure.tools.support;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileStateCacheTest {

    @Test
    void recordsReadWithMtime(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "alpha.txt", "hello");
        FileStateCache cache = new FileStateCache();

        cache.recordRead(context(dir), file);

        assertThat(cache.hasBeenRead(context(dir), file)).isTrue();
    }

    @Test
    void detectsExternalModificationAfterRead(@TempDir Path dir) throws Exception {
        Path file = writeFile(dir, "alpha.txt", "hello");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(context(dir), file);

        Thread.sleep(20);
        Files.writeString(file, "modified externally");

        assertThat(cache.isStale(context(dir), file)).isTrue();
    }

    @Test
    void unreadFileIsNeitherKnownNorStale(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "alpha.txt", "hello");
        FileStateCache cache = new FileStateCache();

        assertThat(cache.hasBeenRead(context(dir), file)).isFalse();
        assertThat(cache.isStale(context(dir), file)).isFalse();
    }

    @Test
    void clearedOnSessionEnd(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "alpha.txt", "hello");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(context(dir), file);

        cache.clear();

        assertThat(cache.hasBeenRead(context(dir), file)).isFalse();
    }

    @Test
    void readingAgainRefreshesMtime(@TempDir Path dir) throws Exception {
        Path file = writeFile(dir, "alpha.txt", "hello");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(context(dir), file);

        Thread.sleep(20);
        Files.writeString(file, "external change");
        assertThat(cache.isStale(context(dir), file)).isTrue();

        cache.recordRead(context(dir), file);
        assertThat(cache.isStale(context(dir), file)).isFalse();
    }

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static ExecutionContext context(Path dir) {
        return ExecutionContext.of(
                RunId.of("file-state-test"), WorkspaceId.fromPath(dir), dir,
                new CancellationToken(), AgentBudget.unlimited());
    }
}
