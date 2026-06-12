package com.anthropic.cclc.domain.skill;

import java.util.List;

/**
 * Loads skills during application assembly.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public interface SkillSource {

    List<Skill> load();
}
