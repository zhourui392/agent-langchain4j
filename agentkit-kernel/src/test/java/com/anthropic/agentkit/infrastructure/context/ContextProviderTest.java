package com.anthropic.agentkit.infrastructure.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContextProviderTest {

    @Test
    void agentsMdProviderReadsFromCwd(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("AGENTS.md"), "project-specific guide");

        Optional<String> result = new AgentsMdProvider().provide(dir);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("project-specific guide");
    }

    @Test
    void agentsMdProviderMergesParentDirectories(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("AGENTS.md"), "ROOT guidance");
        Path child = root.resolve("project");
        Files.createDirectories(child);
        Files.writeString(child.resolve("AGENTS.md"), "PROJECT guidance");

        Optional<String> result = new AgentsMdProvider().provide(child);

        assertThat(result).isPresent();
        String content = result.get();
        assertThat(content).contains("ROOT guidance");
        assertThat(content).contains("PROJECT guidance");
        assertThat(content.indexOf("ROOT guidance"))
                .isLessThan(content.indexOf("PROJECT guidance"));
    }

    @Test
    void agentsMdProviderReturnsEmptyWhenAbsent(@TempDir Path dir) {
        Optional<String> result = new AgentsMdProvider().provide(dir);
        assertThat(result).isEmpty();
    }

    @Test
    void gitStatusProviderReturnsEmptyForNonGitDir(@TempDir Path dir) {
        Optional<String> result = new GitStatusProvider().provide(dir);
        assertThat(result).isEmpty();
    }

    @Test
    void cwdProviderReturnsAbsolutePath(@TempDir Path dir) {
        Optional<String> result = new CwdProvider().provide(dir);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(dir.toAbsolutePath().normalize().toString());
    }

    @Test
    void dateProviderReturnsTodayIso(@TempDir Path dir) {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-17T10:00:00Z"), ZoneId.of("UTC"));
        Optional<String> result = new DateProvider(fixed).provide(dir);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("2026-05-17");
    }

    @Test
    void dateProviderUsesIsoLocalFormat(@TempDir Path dir) {
        Optional<String> result = new DateProvider().provide(dir);
        assertThat(result).isPresent();
        LocalDate parsed = LocalDate.parse(result.get());
        assertThat(parsed).isNotNull();
    }
}
