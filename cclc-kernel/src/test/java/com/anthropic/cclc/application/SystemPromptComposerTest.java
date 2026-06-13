package com.anthropic.cclc.application;

import com.anthropic.cclc.application.SystemPromptComposer.SystemPrompt;
import com.anthropic.cclc.infrastructure.context.AgentsMdProvider;
import com.anthropic.cclc.infrastructure.context.CwdProvider;
import com.anthropic.cclc.infrastructure.context.DateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptComposerTest {

    @Test
    void producesStablePrefixAcrossInvocations(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("AGENTS.md"), "stable guidance");

        SystemPromptComposer composer = new SystemPromptComposer(
                "system instructions",
                List.of(new AgentsMdProvider(),
                        new DateProvider(fixedClock("2026-05-17T00:00:00Z"))));

        SystemPrompt first = composer.compose(dir);
        SystemPrompt second = composer.compose(dir);

        assertThat(first.stablePrefix()).isEqualTo(second.stablePrefix());
        assertThat(first.stablePrefix()).contains("system instructions");
        assertThat(first.stablePrefix()).contains("stable guidance");
    }

    @Test
    void appendsDynamicSectionsAfterStablePrefix(@TempDir Path dir) {
        SystemPromptComposer composer = new SystemPromptComposer(
                "system instructions",
                List.of(new CwdProvider(),
                        new DateProvider(fixedClock("2026-05-17T00:00:00Z"))));

        SystemPrompt prompt = composer.compose(dir);

        assertThat(prompt.stablePrefix()).doesNotContain("2026-05-17");
        assertThat(prompt.stablePrefix()).doesNotContain(dir.toAbsolutePath().toString());
        assertThat(prompt.dynamicSuffix()).contains("2026-05-17");
        assertThat(prompt.dynamicSuffix()).contains(dir.toAbsolutePath().normalize().toString());
    }

    @Test
    void placesCacheBreakpointBeforeDynamicSection(@TempDir Path dir) {
        SystemPromptComposer composer = new SystemPromptComposer(
                "system instructions",
                List.of(new DateProvider(fixedClock("2026-05-17T00:00:00Z"))));

        SystemPrompt prompt = composer.compose(dir);
        String full = prompt.full();

        int markerIdx = full.indexOf(SystemPromptComposer.DYNAMIC_MARKER);
        int dateIdx = full.indexOf("2026-05-17");
        assertThat(markerIdx).isPositive();
        assertThat(markerIdx).isLessThan(dateIdx);
    }

    @Test
    void emptyDynamicProducesNoMarker(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("AGENTS.md"), "guide");
        SystemPromptComposer composer = new SystemPromptComposer(
                "instructions",
                List.of(new AgentsMdProvider()));

        SystemPrompt prompt = composer.compose(dir);

        assertThat(prompt.dynamicSuffix()).isEmpty();
        assertThat(prompt.full()).doesNotContain(SystemPromptComposer.DYNAMIC_MARKER);
    }

    private static Clock fixedClock(String iso) {
        return Clock.fixed(Instant.parse(iso), ZoneId.of("UTC"));
    }
}
