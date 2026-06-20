package com.anthropic.agentkit.application.coding;

import com.anthropic.agentkit.domain.coding.CodingPlan;
import com.anthropic.agentkit.domain.coding.CodingTask;

/**
 * Decomposes a coding requirement into an ordered {@link CodingPlan}.
 *
 * <p>Planner role in the single-pass pipeline (plan → patch → verdict).
 * Implementations are expected to delegate the constrained-turn boilerplate
 * to the kernel {@code StructuredAgent} and own only the role config plus
 * payload-to-VO mapping.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-20
 */
public interface CodingPlanner {

    CodingPlan createPlan(CodingTask task);
}
