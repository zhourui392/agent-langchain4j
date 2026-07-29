package com.anthropic.agentkit.application.recovery;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.run.RunEvent;

import java.util.List;
import java.util.Objects;

/** Loads and projects run facts; deliberately has no ToolRegistry or execution entry point. */
public final class RunEventResumer {

    private final RunEventStore store;
    private final RunEventProjector projector;

    public RunEventResumer(RunEventStore store) {
        this(store, new RunEventProjector());
    }

    public RunEventResumer(RunEventStore store, RunEventProjector projector) {
        this.store = Objects.requireNonNull(store, "store");
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    public RecoveredRun resume(RunId runId) {
        Objects.requireNonNull(runId, "runId");
        List<RunEvent> events = store.load(runId);
        if (events.isEmpty()) {
            throw new RunNotFoundException(runId);
        }
        return projector.project(events);
    }

    public static final class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(RunId runId) {
            super("run not found: " + runId);
        }
    }
}
