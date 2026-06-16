package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.domain.conversation.CancellationToken;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SigintHandler {

    private static final Logger log = LoggerFactory.getLogger(SigintHandler.class);

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
                    log.warn("SIGINT received: action=cancel_turn");
                    cancellation.cancel();
                }
            }
            case CANCELLING -> {
                if (state.compareAndSet(State.CANCELLING, State.EXIT)) {
                    log.warn("SIGINT received: action=exit_process");
                    exitAction.run();
                }
            }
            case EXIT -> {
                log.warn("SIGINT received: action=already_exiting");
            }
        }
    }

    public void turnFinished() {
        if (state.compareAndSet(State.CANCELLING, State.IDLE)) {
            log.debug("SIGINT state reset after turn finished");
        }
    }
}
