package com.anthropic.cclc.infrastructure.tools.support;

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

        cache.recordRead(file);

        assertThat(cache.hasBeenRead(file)).isTrue();
    }

    @Test
    void detectsExternalModificationAfterRead(@TempDir Path dir) throws Exception {
        Path file = writeFile(dir, "alpha.txt", "hello");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(file);

        Thread.sleep(20);
        Files.writeString(file, "modified externally");

        assertThat(cache.isStale(file)).isTrue();
    }

    @Test
    void unreadFileIsNeitherKnownNorStale(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "alpha.txt", "hello");
        FileStateCache cache = new FileStateCache();

        assertThat(cache.hasBeenRead(file)).isFalse();
        assertThat(cache.isStale(file)).isFalse();
    }

    @Test
    void clearedOnSessionEnd(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "alpha.txt", "hello");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(file);

        cache.clear();

        assertThat(cache.hasBeenRead(file)).isFalse();
    }

    @Test
    void readingAgainRefreshesMtime(@TempDir Path dir) throws Exception {
        Path file = writeFile(dir, "alpha.txt", "hello");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(file);

        Thread.sleep(20);
        Files.writeString(file, "external change");
        assertThat(cache.isStale(file)).isTrue();

        cache.recordRead(file);
        assertThat(cache.isStale(file)).isFalse();
    }

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
