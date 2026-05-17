package com.anthropic.cclc.domain.conversation;

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

    public void onCancel(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (cancelled.get()) {
            callback.run();
            return;
        }
        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            callback.run();
        }
    }

    public static CancellationToken notCancellable() {
        return new CancellationToken();
    }
}
