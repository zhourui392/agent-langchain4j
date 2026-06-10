package com.anthropic.cclc.interfaces.cli;

import com.anthropic.cclc.domain.conversation.CancellationToken;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class SigintHandler {

    public enum State { IDLE, CANCELLING, EXIT }

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final CancellationToken cancellation;
    private final Runnable exitAction;

    public SigintHandler(CancellationToken cancellation, Runnable exitAction) {
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.exitAction = Objects.requireNonNull(exitAction, "exitAction");
    }

    public State state() {
        return state.get();
    }

    public void onSigint() {
        State previous = state.get();
        switch (previous) {
            case IDLE -> {
                if (state.compareAndSet(State.IDLE, State.CANCELLING)) {
                    cancellation.cancel();
                }
            }
            case CANCELLING -> {
                if (state.compareAndSet(State.CANCELLING, State.EXIT)) {
                    exitAction.run();
                }
            }
            case EXIT -> {
                // already exiting; no-op
            }
        }
    }

    public void turnFinished() {
        state.compareAndSet(State.CANCELLING, State.IDLE);
    }
}
