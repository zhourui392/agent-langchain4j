package com.anthropic.agentkit.domain.coding;

import java.util.Objects;

/**
 * Coding aggregate root: owns the single-pass plan → patch → verdict state machine.
 *
 * <p>Transitions enforced here (never in the pipeline):
 * <pre>
 *   PLANNING --adoptPlan--> CODING --applyPatch--> REVIEWING
 *       --recordVerdict(ACCEPT)--> ACCEPTED
 *       --recordVerdict(REJECT/NEEDS_HUMAN)--> REJECTED
 * </pre>
 * Retry (REJECTED→CODING) and NEEDS_HUMAN as a non-terminal belong to a later increment;
 * for now any non-ACCEPT verdict terminates the task.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-20
 */
public final class CodingTask {

    private final String taskId;
    private final String requirement;
    private CodingStatus status;
    private CodingPlan plan;
    private Patch patch;
    private ReviewVerdict verdict;

    private CodingTask(String taskId, String requirement) {
        this.taskId = requireText(taskId, "taskId");
        this.requirement = requireText(requirement, "requirement");
        this.status = CodingStatus.PLANNING;
    }

    public static CodingTask open(String taskId, String requirement) {
        return new CodingTask(taskId, requirement);
    }

    public void adoptPlan(CodingPlan nextPlan) {
        requireStatus(CodingStatus.PLANNING);
        this.plan = Objects.requireNonNull(nextPlan, "nextPlan");
        this.status = CodingStatus.CODING;
    }

    public void applyPatch(Patch nextPatch) {
        requireStatus(CodingStatus.CODING);
        this.patch = Objects.requireNonNull(nextPatch, "nextPatch");
        this.status = CodingStatus.REVIEWING;
    }

    public void recordVerdict(ReviewVerdict nextVerdict) {
        requireStatus(CodingStatus.REVIEWING);
        this.verdict = Objects.requireNonNull(nextVerdict, "nextVerdict");
        this.status = nextVerdict.decision() == Verdict.ACCEPT
                ? CodingStatus.ACCEPTED
                : CodingStatus.REJECTED;
    }

    public String taskId() {
        return taskId;
    }

    public String requirement() {
        return requirement;
    }

    public CodingStatus status() {
        return status;
    }

    public CodingPlan plan() {
        return plan;
    }

    public Patch patch() {
        return patch;
    }

    public ReviewVerdict verdict() {
        return verdict;
    }

    private void requireStatus(CodingStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("cannot transition from " + status + " (requires " + expected + ")");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
