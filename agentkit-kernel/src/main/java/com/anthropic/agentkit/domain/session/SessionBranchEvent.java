package com.anthropic.agentkit.domain.session;

import java.time.Instant;
import java.util.Objects;

/** Append-only facts that define a session branch. */
public sealed interface SessionBranchEvent permits SessionBranchEvent.BranchCreated {

    int CURRENT_SCHEMA_VERSION = 1;

    SessionBranch branch();

    long sequence();

    record BranchCreated(
            int schemaVersion,
            long sequence,
            Instant occurredAt,
            SessionBranch branch) implements SessionBranchEvent {

        public BranchCreated {
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported session branch schema version: " + schemaVersion);
            }
            if (sequence <= 0) {
                throw new IllegalArgumentException("branch event sequence must be positive");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(branch, "branch");
        }
    }
}
