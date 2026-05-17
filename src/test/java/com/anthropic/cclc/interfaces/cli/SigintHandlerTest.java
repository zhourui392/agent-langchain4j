package com.anthropic.cclc.interfaces.cli;

import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.interfaces.cli.SigintHandler.State;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SigintHandlerTest {

    @Test
    void firstSigintCancelsCurrentTurn() {
        CancellationToken token = new CancellationToken();
        AtomicBoolean exited = new AtomicBoolean(false);
        SigintHandler handler = new SigintHandler(token, () -> exited.set(true));

        handler.onSigint();

        assertThat(handler.state()).isEqualTo(State.CANCELLING);
        assertThat(token.isCancelled()).isTrue();
        assertThat(exited).isFalse();
    }

    @Test
    void secondSigintExitsProcess() {
        CancellationToken token = new CancellationToken();
        AtomicBoolean exited = new AtomicBoolean(false);
        SigintHandler handler = new SigintHandler(token, () -> exited.set(true));

        handler.onSigint();
        handler.onSigint();

        assertThat(handler.state()).isEqualTo(State.EXIT);
        assertThat(exited).isTrue();
    }

    @Test
    void turnFinishedReturnsToIdle() {
        CancellationToken token = new CancellationToken();
        SigintHandler handler = new SigintHandler(token, () -> {});

        handler.onSigint();
        handler.turnFinished();

        assertThat(handler.state()).isEqualTo(State.IDLE);
    }

    @Test
    void sigintAfterTurnFinishedFiresFreshCancel() {
        CancellationToken token = new CancellationToken();
        AtomicBoolean exited = new AtomicBoolean(false);
        SigintHandler handler = new SigintHandler(token, () -> exited.set(true));

        handler.onSigint();
        handler.turnFinished();
        handler.onSigint();

        assertThat(handler.state()).isEqualTo(State.CANCELLING);
        assertThat(exited).isFalse();
    }
}
