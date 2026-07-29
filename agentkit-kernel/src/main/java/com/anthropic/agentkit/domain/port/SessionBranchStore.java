package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.session.SessionBranchEvent;
import com.anthropic.agentkit.domain.session.SessionBranchId;

import java.util.List;

/** Append-only persistence port for immutable session branch facts. */
public interface SessionBranchStore {

    void append(SessionBranchEvent event);

    List<SessionBranchEvent> load(SessionBranchId branchId);
}
