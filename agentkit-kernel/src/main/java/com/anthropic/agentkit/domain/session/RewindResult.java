package com.anthropic.agentkit.domain.session;

import com.anthropic.agentkit.domain.checkpoint.CheckpointId;
import com.anthropic.agentkit.domain.conversation.Conversation;

import java.util.List;
import java.util.Objects;

/** Explicitly separates conversation projection, file compensation and residual effects. */
public record RewindResult(
        SessionBranch branch,
        Conversation conversation,
        List<CheckpointId> restoredCheckpoints,
        List<CheckpointId> unrestoredCheckpoints,
        List<ResidualSideEffect> residualSideEffects) {

    public RewindResult {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(conversation, "conversation");
        restoredCheckpoints = List.copyOf(
                Objects.requireNonNull(restoredCheckpoints, "restoredCheckpoints"));
        unrestoredCheckpoints = List.copyOf(
                Objects.requireNonNull(unrestoredCheckpoints, "unrestoredCheckpoints"));
        residualSideEffects = List.copyOf(
                Objects.requireNonNull(residualSideEffects, "residualSideEffects"));
    }
}
