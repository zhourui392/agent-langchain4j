package com.anthropic.agentkit.infrastructure.skill;

import com.anthropic.agentkit.domain.skill.Skill;
import com.anthropic.agentkit.domain.skill.SkillCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCatalogContextProviderTest {

    @Test
    void emptyCatalogProvidesNoContext(@TempDir Path dir) {
        SkillCatalogContextProvider provider = new SkillCatalogContextProvider(SkillCatalog.of(List.of()));

        assertThat(provider.key()).isEqualTo("skills");
        assertThat(provider.isDynamic()).isFalse();
        assertThat(provider.provide(dir)).isEmpty();
    }

    @Test
    void nonEmptyCatalogProvidesRenderedCatalog(@TempDir Path dir) {
        SkillCatalog catalog = SkillCatalog.of(List.of(new Skill(
                "es-slow-query", "Diagnose slow ES queries.", "# ES", dir.resolve("es-slow-query"))));
        SkillCatalogContextProvider provider = new SkillCatalogContextProvider(catalog);

        assertThat(provider.provide(dir)).contains(
                catalog.renderCatalog());
    }
}
