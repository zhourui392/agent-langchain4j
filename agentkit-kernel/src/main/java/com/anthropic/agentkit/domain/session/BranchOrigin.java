package com.anthropic.agentkit.domain.session;

/** Why a session branch was created. */
public enum BranchOrigin {
    ROOT,
    FORK,
    REWIND
}
