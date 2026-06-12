package com.anthropic.cclc.infrastructure.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillFrontmatterParserTest {

    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();

    @Test
    void parsesNameDescriptionAndBody() {
        SkillFrontmatterParser.ParsedSkill parsed = parser.parse(Path.of("SKILL.md"), """
                ---
                name: es-slow-query
                description: Diagnose slow ES queries.
                allowed-tools:
                  - Read
                ---

                # Body
                Use ES profile output.
                """);

        assertThat(parsed.name()).contains("es-slow-query");
        assertThat(parsed.description()).isEqualTo("Diagnose slow ES queries.");
        assertThat(parsed.body()).contains("# Body", "Use ES profile output.");
    }

    @Test
    void acceptsMissingNameSoDirectoryNameCanBeUsed() {
        SkillFrontmatterParser.ParsedSkill parsed = parser.parse(Path.of("SKILL.md"), """
                ---
                description: Trace refund chain issues.
                ---
                # Refund Trace
                """);

        assertThat(parsed.name()).isEmpty();
        assertThat(parsed.description()).isEqualTo("Trace refund chain issues.");
    }

    @Test
    void supportsMultilineYamlDescription() {
        SkillFrontmatterParser.ParsedSkill parsed = parser.parse(Path.of("SKILL.md"), """
                ---
                description: >
                  Diagnose ES timeout,
                  high took, and P99 spikes.
                ---
                # ES
                """);

        assertThat(parsed.description())
                .isEqualTo("Diagnose ES timeout, high took, and P99 spikes.");
    }

    @Test
    void rejectsMissingDescription() {
        assertThatThrownBy(() -> parser.parse(Path.of("SKILL.md"), """
                ---
                name: es-slow-query
                ---
                # Body
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void rejectsMalformedYaml() {
        assertThatThrownBy(() -> parser.parse(Path.of("SKILL.md"), """
                ---
                description: [unterminated
                ---
                # Body
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void rejectsMissingFrontmatter() {
        assertThatThrownBy(() -> parser.parse(Path.of("SKILL.md"), "# Body only"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontmatter");
    }
}
