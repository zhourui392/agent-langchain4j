package com.anthropic.cclc.domain.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable registry of loaded skills.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class SkillCatalog {

    private static final String CATALOG_HEADER = """
            以下 Skill 可通过 Skill 工具按需展开。仅当 description 与当前问题匹配时调用；\
            调用一次即获得完整操作指引，禁止凭记忆复述未展开的 Skill 内容。""";

    private final Map<String, Skill> byName;

    private SkillCatalog(Map<String, Skill> byName) {
        this.byName = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
    }

    public static SkillCatalog of(List<Skill> skills) {
        Objects.requireNonNull(skills, "skills");
        Map<String, Skill> byName = new LinkedHashMap<>();
        for (Skill skill : skills) {
            Skill previous = byName.putIfAbsent(skill.name(), skill);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate skill: " + skill.name());
            }
        }
        return new SkillCatalog(byName);
    }

    public Optional<Skill> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    public String renderCatalog() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(CATALOG_HEADER);
        for (Skill skill : byName.values()) {
            sb.append('\n')
                    .append("- ")
                    .append(skill.name())
                    .append(": ")
                    .append(skill.description());
        }
        return sb.toString();
    }

    public List<String> names() {
        return List.copyOf(new ArrayList<>(byName.keySet()));
    }
}
