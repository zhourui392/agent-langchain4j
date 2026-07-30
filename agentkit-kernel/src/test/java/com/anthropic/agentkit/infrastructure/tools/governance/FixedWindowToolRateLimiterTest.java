package com.anthropic.agentkit.infrastructure.tools.governance;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author alex
 */
class FixedWindowToolRateLimiterTest {

    @Test
    void enforcesPerToolLimitAndResetsOnTheNextWindow() {
        AtomicLong now = new AtomicLong(100);
        FixedWindowToolRateLimiter limiter = new FixedWindowToolRateLimiter(
                2, Duration.ofNanos(10), now::get);
        ExecutionContext context = ExecutionContext.at(Path.of("."));

        assertThat(limiter.tryAcquire("LogQuery", context)).isTrue();
        assertThat(limiter.tryAcquire("LogQuery", context)).isTrue();
        assertThat(limiter.tryAcquire("LogQuery", context)).isFalse();
        assertThat(limiter.tryAcquire("MysqlRead", context)).isTrue();

        now.addAndGet(10);
        assertThat(limiter.tryAcquire("LogQuery", context)).isTrue();
    }
}
