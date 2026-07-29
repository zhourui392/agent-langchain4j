package com.anthropic.agentkit.domain.conversation;

import com.anthropic.agentkit.domain.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Conversation {

    private final SessionId sessionId;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final ToolUseInvariantChecker invariants = new ToolUseInvariantChecker();
    private CompactionBoundary lastCompaction;

    public Conversation(SessionId sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public void append(ChatMessage message) {
        Objects.requireNonNull(message, "message");
        invariants.onAppend(message);
        messages.add(message);
    }

    public Optional<CompactionBoundary> lastCompaction() {
        return Optional.ofNullable(lastCompaction);
    }

    public void installCompaction(
            CompactionBoundary boundary, List<ChatMessage> retainedMessages) {
        Objects.requireNonNull(boundary, "boundary");
        List<ChatMessage> retained = List.copyOf(
                Objects.requireNonNull(retainedMessages, "retainedMessages"));
        validateCompactionSource(boundary, retained);
        List<ChatMessage> candidate = new ArrayList<>(retained.size() + 1);
        candidate.add(boundary.asMessage());
        candidate.addAll(retained);
        ToolUseInvariantChecker.validateCompleteProjection(candidate);
        messages.clear();
        messages.addAll(candidate);
        lastCompaction = boundary;
    }

    private void validateCompactionSource(
            CompactionBoundary boundary, List<ChatMessage> retained) {
        if (invariants.hasPendingBatch()) {
            throw new IllegalStateException("cannot compact while tool batch is pending");
        }
        if (boundary.sourceStartInclusive() != 0
                || boundary.sourceEndExclusive() > messages.size()) {
            throw new IllegalArgumentException("compaction boundary is outside conversation");
        }
        List<ChatMessage> expected = messages.subList(
                boundary.sourceEndExclusive(), messages.size());
        if (!expected.equals(retained)) {
            throw new IllegalArgumentException("retained messages do not match compaction boundary");
        }
    }
}
