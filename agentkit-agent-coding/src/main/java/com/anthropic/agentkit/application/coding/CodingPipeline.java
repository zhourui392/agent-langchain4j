package com.anthropic.agentkit.application.coding;

import com.anthropic.agentkit.domain.coding.CodingPlan;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.coding.Patch;
import com.anthropic.agentkit.domain.coding.ReviewVerdict;

import java.util.Objects;

/**
 * Single-pass coding workflow: orchestrates the plan → patch → review pipeline
 * by driving the {@link CodingTask} aggregate through its state machine.
 *
 * <p>Pure orchestration: every transition guard and the verdict-to-status
 * decision live in {@link CodingTask}, never here. Retry / human-escalation is a
 * later increment and belongs to the aggregate, so this pipeline carries no
 * {@code if (retryCount < n)} branch.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-20
 */
public final class CodingPipeline {

    private final CodingPlanner planner;
    private final CodingPatcher patcher;
    private final CodingReviewer reviewer;

    public CodingPipeline(CodingPlanner planner, CodingPatcher patcher, CodingReviewer reviewer) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.patcher = Objects.requireNonNull(patcher, "patcher");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer");
    }

    public CodingTask run(CodingTask task) {
        Objects.requireNonNull(task, "task");
        CodingPlan plan = planner.createPlan(task);
        task.adoptPlan(plan);
        Patch patch = patcher.producePatch(task, plan);
        task.applyPatch(patch);
        ReviewVerdict verdict = reviewer.review(task, patch);
        task.recordVerdict(verdict);
        return task;
    }
}
