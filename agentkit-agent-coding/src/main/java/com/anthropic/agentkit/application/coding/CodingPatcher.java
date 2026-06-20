package com.anthropic.agentkit.application.coding;

import com.anthropic.agentkit.domain.coding.CodingPlan;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.coding.Patch;

/**
 * Produces a {@link Patch} that resolves the requirement under a given plan.
 *
 * <p>Coder role in the single-pass pipeline. Unlike the planner/reviewer, the
 * coder holds write capability — it may carry file read/write tools so it can
 * actually mutate the working tree before submitting the structured patch.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-20
 */
public interface CodingPatcher {

    Patch producePatch(CodingTask task, CodingPlan plan);
}
