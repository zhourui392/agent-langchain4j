package com.anthropic.agentkit.infrastructure.skill;

import com.anthropic.agentkit.domain.skill.Skill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectorySkillSourceTest {

    @Test
    void loadsSkillsFromChildDirectories(@TempDir Path root) throws IOException {
        writeSkill(root.resolve("es-slow-query"), """
                ---
                description: Diagnose slow ES queries.
                ---
                # ES Slow Query
                """);
        writeSkill(root.resolve("trade-refund-trace"), """
                ---
                name: trade-refund-trace
                description: Trace refund chain issues.
                ---
                # Refund Trace
                """);

        List<Skill> skills = new DirectorySkillSource(root, new SkillFrontmatterParser()).load();

        assertThat(skills).extracting(Skill::name)
                .containsExactly("es-slow-query", "trade-refund-trace");
        assertThat(skills.get(0).baseDir()).isEqualTo(root.resolve("es-slow-query").normalize());
    }

    @Test
    void rejectsExplicitNameThatDiffersFromDirectory(@TempDir Path root) throws IOException {
        writeSkill(root.resolve("es-slow-query"), """
                ---
                name: other-skill
                description: Diagnose slow ES queries.
                ---
                # ES
                """);

        DirectorySkillSource source = new DirectorySkillSource(root, new SkillFrontmatterParser());

        assertThatThrownBy(source::load)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directory");
    }

    @Test
    void rejectsInvalidDirectoryName(@TempDir Path root) throws IOException {
        writeSkill(root.resolve("Bad_Name"), """
                ---
                description: Diagnose slow ES queries.
                ---
                # ES
                """);

        DirectorySkillSource source = new DirectorySkillSource(root, new SkillFrontmatterParser());

        assertThatThrownBy(source::load)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsMissingRootDirectory(@TempDir Path root) {
        Path missing = root.resolve("missing");
        DirectorySkillSource source = new DirectorySkillSource(missing, new SkillFrontmatterParser());

        assertThatThrownBy(source::load)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skills root");
    }

    @Test
    void failFastMessageIncludesSkillFileWhenDomainValidationFails(@TempDir Path root) throws IOException {
        writeSkill(root.resolve("es-slow-query"), """
                ---
                description: Diagnose slow ES queries.
                ---

                """);
        DirectorySkillSource source = new DirectorySkillSource(root, new SkillFrontmatterParser());

        assertThatThrownBy(source::load)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL.md")
                .hasMessageContaining("body");
    }

    private static void writeSkill(Path directory, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), content);
    }
}
