package com.anthropic.agentkit.application.context;

import com.anthropic.agentkit.domain.agent.StopReason;

import java.util.Objects;
import java.util.Optional;

/** Result of applying context governance before or after a provider request. */
public record ContextDecision(
        Outcome outcome,
        Optional<StopReason> stopReason,
        Optional<String> errorDetail) {

    public enum Outcome { UNCHANGED, COMPACTED, NOT_APPLICABLE, STOPPED }

    public ContextDecision {
        Objects.requireNonNull(outcome, "outcome");
        stopReason = Objects.requireNonNull(stopReason, "stopReason");
        errorDetail = Objects.requireNonNull(errorDetail, "errorDetail");
        if ((outcome == Outcome.STOPPED) != stopReason.isPresent()) {
            throw new IllegalArgumentException("only a stopped decision has a stop reason");
        }
    }

    public boolean compacted() {
        return outcome == Outcome.COMPACTED;
    }

    public boolean notApplicable() {
        return outcome == Outcome.NOT_APPLICABLE;
    }

    public static ContextDecision unchanged() {
        return new ContextDecision(Outcome.UNCHANGED, Optional.empty(), Optional.empty());
    }

    public static ContextDecision compactedContext() {
        return new ContextDecision(Outcome.COMPACTED, Optional.empty(), Optional.empty());
    }

    public static ContextDecision notApplicableDecision() {
        return new ContextDecision(Outcome.NOT_APPLICABLE, Optional.empty(), Optional.empty());
    }

    public static ContextDecision stopped(StopReason reason, String detail) {
        String safeDetail = detail == null || detail.isBlank() ? reason.name() : detail;
        return new ContextDecision(
                Outcome.STOPPED, Optional.of(reason), Optional.of(safeDetail));
    }
}
