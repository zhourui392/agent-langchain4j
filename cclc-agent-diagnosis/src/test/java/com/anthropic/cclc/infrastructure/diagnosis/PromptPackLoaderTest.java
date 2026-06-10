package com.anthropic.cclc.infrastructure.diagnosis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PromptPackLoaderTest {

    @Test
    void loadsMarkdownFilesInNameOrder(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("b.md"), "second");
        Files.writeString(dir.resolve("a.md"), "first");
        Files.writeString(dir.resolve("ignore.txt"), "ignored");

        String prompt = new PromptPackLoader().load(dir);

        assertThat(prompt).contains("## a.md\nfirst");
        assertThat(prompt).contains("## b.md\nsecond");
        assertThat(prompt).doesNotContain("ignored");
        assertThat(prompt.indexOf("a.md")).isLessThan(prompt.indexOf("b.md"));
    }
}
