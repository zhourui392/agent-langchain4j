package com.anthropic.cclc.domain.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillTest {

    @Test
    void createsImmutableSkillWhenFieldsAreValid() {
        Skill skill = new Skill("es-slow-query", "Diagnose slow ES queries",
                "# ES slow query\nRead references when needed.", Path.of("skills/es-slow-query"));

        assertThat(skill.name()).isEqualTo("es-slow-query");
        assertThat(skill.description()).isEqualTo("Diagnose slow ES queries");
        assertThat(skill.body()).contains("ES slow query");
        assertThat(skill.baseDir()).isEqualTo(Path.of("skills/es-slow-query"));
    }

    @Test
    void preservesBodyTextExactly() {
        String body = "# ES slow query\n\nKeep formatting.\n";

        Skill skill = new Skill("es-slow-query", "Diagnose slow ES queries",
                body, Path.of("skills/es-slow-query"));

        assertThat(skill.body()).isEqualTo(body);
    }

    @Test
    void rejectsInvalidName() {
        assertThatThrownBy(() -> buildSkill("Bad_Name", "valid description", "body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsBaseDirectoryThatDoesNotMatchName() {
        assertThatThrownBy(() -> new Skill("es-slow-query", "valid description", "body",
                Path.of("skills/trade-refund-trace")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directory");
    }

    @Test
    void rejectsBlankDescription() {
        assertThatThrownBy(() -> buildSkill("es-slow-query", " ", "body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void rejectsOversizedDescription() {
        String description = "x".repeat(1025);

        assertThatThrownBy(() -> buildSkill("es-slow-query", description, "body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void rejectsBlankBody() {
        assertThatThrownBy(() -> buildSkill("es-slow-query", "valid description", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }

    @Test
    void rejectsOversizedBody() {
        String body = "x".repeat(64 * 1024 + 1);

        assertThatThrownBy(() -> buildSkill("es-slow-query", "valid description", body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }

    private static Skill buildSkill(String name, String description, String body) {
        return new Skill(name, description, body, Path.of("skills").resolve(name));
    }
}
