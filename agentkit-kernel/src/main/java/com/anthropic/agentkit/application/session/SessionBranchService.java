package com.anthropic.agentkit.application.session;

import com.anthropic.agentkit.application.recovery.RunEventProjector;
import com.anthropic.agentkit.domain.checkpoint.CheckpointId;
import com.anthropic.agentkit.domain.checkpoint.FileCheckpointException;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.port.FileCheckpointProvider;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.port.SessionBranchStore;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.session.BranchOrigin;
import com.anthropic.agentkit.domain.session.ResidualSideEffect;
import com.anthropic.agentkit.domain.session.RewindMode;
import com.anthropic.agentkit.domain.session.RewindResult;
import com.anthropic.agentkit.domain.session.RunEventPointer;
import com.anthropic.agentkit.domain.session.SessionBranch;
import com.anthropic.agentkit.domain.session.SessionBranchEvent;
import com.anthropic.agentkit.domain.session.SessionBranchId;
import com.anthropic.agentkit.domain.session.SessionBranchScope;
import com.anthropic.agentkit.domain.session.SessionBranchUnavailableException;
import com.anthropic.agentkit.domain.tool.ToolSideEffect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Forks immutable event projections and optionally compensates checkpointed files. */
public final class SessionBranchService {

    private final RunEventStore runEvents;
    private final SessionBranchStore branches;
    private final FileCheckpointProvider checkpoints;
    private final RunEventProjector projector = new RunEventProjector();

    public SessionBranchService(
            RunEventStore runEvents,
            SessionBranchStore branches,
            FileCheckpointProvider checkpoints) {
        this.runEvents = Objects.requireNonNull(runEvents, "runEvents");
        this.branches = Objects.requireNonNull(branches, "branches");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    }

    public SessionBranch createRoot(
            SessionBranchScope scope, RunEventPointer head) {
        requireEvent(scope, head);
        return persist(SessionBranch.root(SessionBranchId.fresh(), scope, head));
    }

    public SessionBranch fork(
            SessionBranchScope scope,
            SessionBranchId parentId,
            RunEventPointer point) {
        SessionBranch parent = load(scope, parentId);
        requirePoint(parent, point);
        return createChild(parent, point, BranchOrigin.FORK);
    }

    public RewindResult rewind(
            SessionBranchScope scope,
            SessionBranchId parentId,
            RunEventPointer point,
            RewindMode mode) {
        Objects.requireNonNull(mode, "mode");
        SessionBranch parent = load(scope, parentId);
        List<RunEvent> source = eventsThrough(parent, point);
        Conversation conversation = projector.project(prefix(source, point)).conversation();
        SessionBranch child = createChild(parent, point, BranchOrigin.REWIND);
        return compensate(child, conversation, source, point, mode);
    }

    public SessionBranch load(
            SessionBranchScope scope, SessionBranchId branchId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(branchId, "branchId");
        List<SessionBranchEvent> events = branches.load(branchId);
        if (events.isEmpty()) {
            throw new SessionBranchUnavailableException();
        }
        SessionBranch branch = events.getLast().branch();
        if (!branch.scope().equals(scope)) {
            throw new SessionBranchUnavailableException();
        }
        return branch;
    }

    private SessionBranch createChild(
            SessionBranch parent, RunEventPointer point, BranchOrigin origin) {
        return persist(SessionBranch.child(
                SessionBranchId.fresh(), parent, point, origin));
    }

    private SessionBranch persist(SessionBranch branch) {
        branches.append(new SessionBranchEvent.BranchCreated(
                SessionBranchEvent.CURRENT_SCHEMA_VERSION,
                1, Instant.now(), branch));
        return branch;
    }

    private List<RunEvent> eventsThrough(
            SessionBranch parent, RunEventPointer point) {
        requirePoint(parent, point);
        List<RunEvent> events = runEvents.load(parent.head().runId());
        int head = Math.toIntExact(parent.head().sequence());
        if (events.size() < head) {
            throw new SessionBranchUnavailableException();
        }
        return List.copyOf(events.subList(0, head));
    }

    private void requirePoint(SessionBranch parent, RunEventPointer point) {
        if (!parent.head().runId().equals(point.runId())
                || point.sequence() > parent.head().sequence()) {
            throw new SessionBranchUnavailableException();
        }
        requireEvent(parent.scope(), point);
    }

    private void requireEvent(
            SessionBranchScope scope, RunEventPointer pointer) {
        List<RunEvent> events = runEvents.load(pointer.runId());
        int index = Math.toIntExact(pointer.sequence() - 1);
        if (index < 0 || index >= events.size()) {
            throw new SessionBranchUnavailableException();
        }
        var metadata = events.get(index).metadata();
        if (!metadata.sessionId().equals(scope.sessionId())
                || !metadata.workspaceId().equals(scope.workspaceId())) {
            throw new SessionBranchUnavailableException();
        }
    }

    private static List<RunEvent> prefix(
            List<RunEvent> source, RunEventPointer point) {
        return List.copyOf(source.subList(0, Math.toIntExact(point.sequence())));
    }

    private RewindResult compensate(
            SessionBranch child, Conversation conversation,
            List<RunEvent> source, RunEventPointer point, RewindMode mode) {
        List<RunEvent.ToolSideEffectObserved> effects = effectsAfter(source, point);
        List<CheckpointId> restored = new ArrayList<>();
        List<CheckpointId> unrestored = new ArrayList<>();
        List<ResidualSideEffect> residuals = new ArrayList<>();
        Collections.reverse(effects);
        for (RunEvent.ToolSideEffectObserved event : effects) {
            compensate(event, child.scope(), mode, restored, unrestored, residuals);
        }
        return new RewindResult(
                child, conversation, restored, unrestored, residuals);
    }

    private static List<RunEvent.ToolSideEffectObserved> effectsAfter(
            List<RunEvent> source, RunEventPointer point) {
        return source.stream()
                .filter(event -> event.metadata().sequence() > point.sequence())
                .filter(RunEvent.ToolSideEffectObserved.class::isInstance)
                .map(RunEvent.ToolSideEffectObserved.class::cast)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private void compensate(
            RunEvent.ToolSideEffectObserved event,
            SessionBranchScope scope,
            RewindMode mode,
            List<CheckpointId> restored,
            List<CheckpointId> unrestored,
            List<ResidualSideEffect> residuals) {
        switch (event.sideEffect()) {
            case ToolSideEffect.CheckpointedFile file ->
                    compensateFile(event, scope, mode, file.checkpointId(),
                            restored, unrestored, residuals);
            case ToolSideEffect.NonReversible external -> residuals.add(
                    new ResidualSideEffect(pointer(event), event.toolUseId(),
                            external.toolName(), external.detail()));
        }
    }

    private void compensateFile(
            RunEvent.ToolSideEffectObserved event,
            SessionBranchScope scope,
            RewindMode mode,
            CheckpointId checkpoint,
            List<CheckpointId> restored,
            List<CheckpointId> unrestored,
            List<ResidualSideEffect> residuals) {
        if (mode == RewindMode.CONVERSATION_ONLY) {
            unrestored.add(checkpoint);
            return;
        }
        try {
            checkpoints.restore(scope.checkpointOwner(), checkpoint);
            restored.add(checkpoint);
        } catch (FileCheckpointException failure) {
            unrestored.add(checkpoint);
            residuals.add(new ResidualSideEffect(
                    pointer(event), event.toolUseId(), "FileCheckpoint",
                    "file checkpoint could not be restored: " + failure.getMessage()));
        }
    }

    private static RunEventPointer pointer(RunEvent event) {
        return new RunEventPointer(
                event.metadata().runId(), event.metadata().sequence());
    }
}
