package com.anthropic.cclc.infrastructure.skill;

import com.anthropic.cclc.domain.context.ContextProvider;
import com.anthropic.cclc.domain.skill.SkillCatalog;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Renders the skill catalog as a stable prompt-cache context section.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class SkillCatalogContextProvider implements ContextProvider {

    private final SkillCatalog catalog;

    public SkillCatalogContextProvider(SkillCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public String key() {
        return "skills";
    }

    @Override
    public Optional<String> provide(Path workingDirectory) {
        return catalog.isEmpty() ? Optional.empty() : Optional.of(catalog.renderCatalog());
    }
}
