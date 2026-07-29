package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.domain.conversation.CancellationToken;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SigintHandler {

    private static final Logger log = LoggerFactory.getLogger(SigintHandler.class);

    public enum State { IDLE, CANCELLING, EXIT }

    private volatile State state = State.IDLE;
    private CancellationToken activeRun;
    private final Runnable exitAction;

    public SigintHandler(Runnable exitAction) {
        this.exitAction = Objects.requireNonNull(exitAction, "exitAction");
    }

    public State state() {
        return state;
    }

    public synchronized CancellationToken turnStarted() {
        if (activeRun != null || state != State.IDLE) {
            throw new IllegalStateException("a CLI run is already active");
        }
        activeRun = new CancellationToken();
        return activeRun;
    }

    public void onSigint() {
        boolean exit;
        synchronized (this) {
            exit = transitionOnSigint();
        }
        if (exit) {
            exitAction.run();
        }
    }

    private boolean transitionOnSigint() {
        CancellationToken cancellation = activeRun;
        if (cancellation == null) {
            log.debug("SIGINT received while CLI is idle");
            return false;
        }
        return switch (state) {
            case IDLE -> {
                state = State.CANCELLING;
                log.warn("SIGINT received: action=cancel_turn");
                cancellation.cancel();
                yield false;
            }
            case CANCELLING -> {
                state = State.EXIT;
                log.warn("SIGINT received: action=exit_process");
                yield true;
            }
            case EXIT -> {
                log.warn("SIGINT received: action=already_exiting");
                yield false;
            }
        };
    }

    public synchronized void turnFinished(CancellationToken token) {
        Objects.requireNonNull(token, "token");
        if (activeRun == token) {
            activeRun = null;
            if (state == State.CANCELLING) {
                state = State.IDLE;
            }
            log.debug("SIGINT state reset after CLI run finished");
        }
    }
}
