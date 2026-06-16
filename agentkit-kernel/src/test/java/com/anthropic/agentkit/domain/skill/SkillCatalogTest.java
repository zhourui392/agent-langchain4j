package com.anthropic.agentkit.domain.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillCatalogTest {

    @Test
    void findsSkillByNameAndExposesNamesInOrder() {
        Skill es = skill("es-slow-query", "Diagnose ES slow queries");
        Skill refund = skill("trade-refund-trace", "Trace refund issues");

        SkillCatalog catalog = SkillCatalog.of(List.of(es, refund));

        assertThat(catalog.find("es-slow-query")).contains(es);
        assertThat(catalog.find("missing")).isEmpty();
        assertThat(catalog.names()).containsExactly("es-slow-query", "trade-refund-trace");
        assertThat(catalog.isEmpty()).isFalse();
    }

    @Test
    void rejectsDuplicateNames() {
        Skill first = skill("es-slow-query", "Diagnose ES slow queries");
        Skill second = skill("es-slow-query", "Another description");

        assertThatThrownBy(() -> SkillCatalog.of(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate skill");
    }

    @Test
    void rendersCatalogAsStablePromptSection() {
        SkillCatalog catalog = SkillCatalog.of(List.of(
                skill("es-slow-query", "Diagnose slow ES queries when timeout or P99 spikes."),
                skill("trade-refund-trace", "Trace refund chain issues.")));

        String rendered = catalog.renderCatalog();

        assertThat(rendered)
                .contains("Skill")
                .contains("仅当 description")
                .contains("- es-slow-query: Diagnose slow ES queries")
                .contains("- trade-refund-trace: Trace refund chain issues");
    }

    @Test
    void emptyCatalogRendersNoText() {
        SkillCatalog catalog = SkillCatalog.of(List.of());

        assertThat(catalog.isEmpty()).isTrue();
        assertThat(catalog.renderCatalog()).isEmpty();
        assertThat(catalog.names()).isEmpty();
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "# " + name, Path.of("skills").resolve(name));
    }
}
