package com.anthropic.agentkit.domain.conversation;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable cb : callbacks) {
                cb.run();
            }
            callbacks.clear();
        }
    }

    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancellationException("operation cancelled");
        }
    }

    public Registration onCancel(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (cancelled.get()) {
            callback.run();
            return Registration.NO_OP;
        }
        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            callback.run();
            return Registration.NO_OP;
        }
        return () -> callbacks.remove(callback);
    }

    public static CancellationToken notCancellable() {
        return new CancellationToken();
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        Registration NO_OP = () -> { };

        @Override
        void close();
    }
}
