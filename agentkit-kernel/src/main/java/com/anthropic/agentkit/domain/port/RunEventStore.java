package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.run.RunEvent;

import java.util.List;

/** Append-only persistence port for versioned run facts. */
public interface RunEventStore {

    void append(RunEvent event);

    List<RunEvent> load(RunId runId);

    static RunEventStore none() {
        return NoOpRunEventStore.INSTANCE;
    }

    enum NoOpRunEventStore implements RunEventStore {
        INSTANCE;

        @Override public void append(RunEvent event) { }
        @Override public List<RunEvent> load(RunId runId) { return List.of(); }
    }
}
