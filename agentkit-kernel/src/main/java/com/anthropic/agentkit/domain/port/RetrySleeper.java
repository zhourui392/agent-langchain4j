package com.anthropic.agentkit.domain.port;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Replaceable blocking clock used only between bounded provider attempts. */
@FunctionalInterface
public interface RetrySleeper {

    void sleep(Duration delay) throws InterruptedException;

    static RetrySleeper system() {
        return delay -> {
            Objects.requireNonNull(delay, "delay");
            TimeUnit.NANOSECONDS.sleep(delay.toNanos());
        };
    }

    static RetrySleeper immediate() {
        return ignored -> { };
    }
}
