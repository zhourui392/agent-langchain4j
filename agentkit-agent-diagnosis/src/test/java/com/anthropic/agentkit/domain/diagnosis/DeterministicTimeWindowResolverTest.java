package com.anthropic.agentkit.domain.diagnosis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author alex
 */
class DeterministicTimeWindowResolverTest {

    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");
    private final TimeWindowResolver resolver = new DeterministicTimeWindowResolver();

    @Test
    void resolvesChineseRelativeHoursAgainstHostNow() {
        TimeResolution resolution = resolver.resolve(
                "看下最近两个小时的错误日志", NOW, ZoneId.of("UTC"),
                TimeWindowPolicy.withMaximum(Duration.ofHours(24)));

        assertThat(resolution.resolved()).isTrue();
        assertThat(resolution.window()).contains(new TimeWindow(
                Instant.parse("2026-07-30T00:00:00Z"), NOW));
    }

    @Test
    void resolvesYesterdayAcrossHostTimezoneBoundary() {
        TimeResolution resolution = resolver.resolve(
                "昨天的错误日志", NOW, ZoneId.of("Asia/Shanghai"),
                TimeWindowPolicy.withMaximum(Duration.ofDays(2)));

        assertThat(resolution.window()).contains(new TimeWindow(
                Instant.parse("2026-07-28T16:00:00Z"),
                Instant.parse("2026-07-29T16:00:00Z")));
    }

    @Test
    void resolvesTodayFromHostMidnightToHostNow() {
        TimeResolution resolution = resolver.resolve(
                "今天的错误日志", NOW, ZoneId.of("Asia/Shanghai"),
                TimeWindowPolicy.withMaximum(Duration.ofDays(1)));

        assertThat(resolution.window()).contains(new TimeWindow(
                Instant.parse("2026-07-29T16:00:00Z"), NOW));
    }

    @Test
    void resolvesExplicitOffsetRangeToAbsoluteInstants() {
        TimeResolution resolution = resolver.resolve(
                "查询 2026-07-30T08:00:00+08:00 到 2026-07-30T10:00:00+08:00",
                NOW, ZoneId.of("UTC"),
                TimeWindowPolicy.withMaximum(Duration.ofHours(24)));

        assertThat(resolution.window()).contains(new TimeWindow(
                Instant.parse("2026-07-30T00:00:00Z"), NOW));
    }

    @Test
    void resolvesFromTodayClockAgainstHostZone() {
        TimeResolution resolution = resolver.resolve(
                "从今天 09:30 开始", NOW, ZoneId.of("Asia/Shanghai"),
                TimeWindowPolicy.withMaximum(Duration.ofHours(24)));

        assertThat(resolution.window()).contains(new TimeWindow(
                Instant.parse("2026-07-30T01:30:00Z"), NOW));
    }

    @Test
    void rejectsWindowThatExceedsPolicy() {
        TimeResolution resolution = resolver.resolve(
                "最近两天", NOW, ZoneId.of("UTC"),
                TimeWindowPolicy.withMaximum(Duration.ofHours(24)));

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.reasonCode()).isEqualTo("TIME_WINDOW_TOO_LARGE");
    }
}
