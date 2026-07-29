package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.interfaces.cli.SigintHandler.State;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigintHandlerTest {

    @Test
    void firstSigintCancelsCurrentTurn() {
        AtomicBoolean exited = new AtomicBoolean(false);
        SigintHandler handler = new SigintHandler(() -> exited.set(true));
        CancellationToken token = handler.turnStarted();

        handler.onSigint();

        assertThat(handler.state()).isEqualTo(State.CANCELLING);
        assertThat(token.isCancelled()).isTrue();
        assertThat(exited).isFalse();
    }

    @Test
    void secondSigintExitsProcess() {
        AtomicBoolean exited = new AtomicBoolean(false);
        SigintHandler handler = new SigintHandler(() -> exited.set(true));
        handler.turnStarted();

        handler.onSigint();
        handler.onSigint();

        assertThat(handler.state()).isEqualTo(State.EXIT);
        assertThat(exited).isTrue();
    }

    @Test
    void turnFinishedReturnsToIdle() {
        SigintHandler handler = new SigintHandler(() -> {});
        CancellationToken token = handler.turnStarted();

        handler.onSigint();
        handler.turnFinished(token);

        assertThat(handler.state()).isEqualTo(State.IDLE);
    }

    @Test
    void nextTurnReceivesFreshUncancelledToken() {
        AtomicBoolean exited = new AtomicBoolean(false);
        SigintHandler handler = new SigintHandler(() -> exited.set(true));
        CancellationToken first = handler.turnStarted();

        handler.onSigint();
        handler.turnFinished(first);
        CancellationToken second = handler.turnStarted();

        assertThat(second).isNotSameAs(first);
        assertThat(second.isCancelled()).isFalse();
        assertThat(handler.state()).isEqualTo(State.IDLE);
    }

    @Test
    void idleSigintDoesNotPolluteNextRun() {
        AtomicBoolean exited = new AtomicBoolean(false);
        SigintHandler handler = new SigintHandler(() -> exited.set(true));

        handler.onSigint();
        CancellationToken token = handler.turnStarted();

        assertThat(handler.state()).isEqualTo(State.IDLE);
        assertThat(token.isCancelled()).isFalse();
        assertThat(exited).isFalse();
    }

    @Test
    void staleTurnCompletionCannotReleaseTheActiveRun() {
        SigintHandler handler = new SigintHandler(() -> {});
        CancellationToken active = handler.turnStarted();

        handler.turnFinished(new CancellationToken());

        assertThatThrownBy(handler::turnStarted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already active");
        handler.turnFinished(active);
        assertThat(handler.turnStarted().isCancelled()).isFalse();
    }
}
