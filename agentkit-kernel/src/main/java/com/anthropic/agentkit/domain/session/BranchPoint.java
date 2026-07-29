package com.anthropic.agentkit.domain.session;

import java.util.Objects;

/** Immutable parent branch and event coordinate captured by a fork. */
public record BranchPoint(SessionBranchId branchId, RunEventPointer event) {

    public BranchPoint {
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(event, "event");
    }
}
