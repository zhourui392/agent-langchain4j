package com.anthropic.agentkit.infrastructure.tools.governance;

import com.anthropic.agentkit.domain.tool.ExecutionContext;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Bounded per-tool fixed-window rate limiter shared by all runs of one engine.
 *
 * @author alex
 */
public final class FixedWindowToolRateLimiter implements ToolRateLimiter {

    private final int maxCalls;
    private final long windowNanos;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowToolRateLimiter(int maxCalls, Duration window) {
        this(maxCalls, window, System::nanoTime);
    }

    FixedWindowToolRateLimiter(int maxCalls, Duration window, LongSupplier nanoTime) {
        if (maxCalls <= 0) {
            throw new IllegalArgumentException("maxCalls must be positive");
        }
        Duration checked = Objects.requireNonNull(window, "window");
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.maxCalls = maxCalls;
        this.windowNanos = checked.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public boolean tryAcquire(String toolName, ExecutionContext context) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(context, "context");
        long now = nanoTime.getAsLong();
        return windows.computeIfAbsent(toolName, ignored -> new Window(now)).tryAcquire(
                now, maxCalls, windowNanos);
    }

    private static final class Window {
        private long startedAt;
        private int calls;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }

        private synchronized boolean tryAcquire(long now, int limit, long duration) {
            if (now - startedAt >= duration || now < startedAt) {
                startedAt = now;
                calls = 0;
            }
            if (calls >= limit) {
                return false;
            }
            calls++;
            return true;
        }
    }
}
