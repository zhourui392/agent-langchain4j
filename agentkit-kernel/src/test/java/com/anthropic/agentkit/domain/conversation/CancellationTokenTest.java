package com.anthropic.agentkit.domain.conversation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationTokenTest {

    @Test
    void initiallyNotCancelled() {
        CancellationToken token = new CancellationToken();
        assertThat(token.isCancelled()).isFalse();
    }

    @Test
    void cancelledIsVisibleAcrossThreads() throws InterruptedException {
        CancellationToken token = new CancellationToken();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicBoolean seen = new AtomicBoolean(false);

        Thread observer = new Thread(() -> {
            ready.countDown();
            while (!token.isCancelled()) {
                Thread.onSpinWait();
            }
            seen.set(true);
        });
        observer.start();

        ready.await();
        token.cancel();
        observer.join(2000);

        assertThat(seen).isTrue();
    }

    @Test
    void cancelIsIdempotent() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        token.cancel();
        token.cancel();
        assertThat(token.isCancelled()).isTrue();
    }

    @Test
    void throwIfCancelledThrowsAfterCancel() {
        CancellationToken token = new CancellationToken();
        token.throwIfCancelled();

        token.cancel();
        assertThatThrownBy(token::throwIfCancelled)
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void onCancelCallbackFiresOnceOnCancel() {
        CancellationToken token = new CancellationToken();
        AtomicBoolean called = new AtomicBoolean(false);
        token.onCancel(() -> called.set(true));

        token.cancel();
        assertThat(called).isTrue();
    }

    @Test
    void onCancelRegisteredAfterCancelFiresImmediately() {
        CancellationToken token = new CancellationToken();
        token.cancel();

        AtomicBoolean called = new AtomicBoolean(false);
        token.onCancel(() -> called.set(true));

        assertThat(called).isTrue();
    }

    @Test
    void multipleCallbacksAllFireOnce() throws InterruptedException {
        CancellationToken token = new CancellationToken();
        CountDownLatch latch = new CountDownLatch(3);
        token.onCancel(latch::countDown);
        token.onCancel(latch::countDown);
        token.onCancel(latch::countDown);

        token.cancel();
        token.cancel();

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
    }
}
