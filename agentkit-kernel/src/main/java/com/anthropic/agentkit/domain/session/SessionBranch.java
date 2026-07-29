package com.anthropic.agentkit.domain.session;

import java.util.Objects;
import java.util.Optional;

/** Immutable aggregate projection for one append-only session branch. */
public record SessionBranch(
        SessionBranchId id,
        SessionBranchScope scope,
        BranchOrigin origin,
        Optional<BranchPoint> parentPoint,
        RunEventPointer head) {

    public SessionBranch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(parentPoint, "parentPoint");
        Objects.requireNonNull(head, "head");
        if ((origin == BranchOrigin.ROOT) != parentPoint.isEmpty()) {
            throw new IllegalArgumentException(
                    "only a root branch may omit its parent point");
        }
    }

    public static SessionBranch root(
            SessionBranchId id, SessionBranchScope scope, RunEventPointer head) {
        return new SessionBranch(id, scope, BranchOrigin.ROOT, Optional.empty(), head);
    }

    public static SessionBranch child(
            SessionBranchId id, SessionBranch parent,
            RunEventPointer point, BranchOrigin origin) {
        if (origin == BranchOrigin.ROOT) {
            throw new IllegalArgumentException("a child branch cannot have ROOT origin");
        }
        return new SessionBranch(id, parent.scope(), origin,
                Optional.of(new BranchPoint(parent.id(), point)), point);
    }
}
