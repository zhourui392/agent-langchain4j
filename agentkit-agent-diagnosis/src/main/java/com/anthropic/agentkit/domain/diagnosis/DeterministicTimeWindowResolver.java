package com.anthropic.agentkit.domain.diagnosis;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small deterministic resolver for the supported relative and ISO-8601 expressions.
 *
 * @author alex
 */
public final class DeterministicTimeWindowResolver implements TimeWindowResolver {

    private static final Pattern RECENT_ZH = Pattern.compile(
            "最近\\s*([0-9]+|一|两|二|三|四|五|六|七|八|九|十)\\s*个?\\s*(分钟|小时|天)");
    private static final Pattern RECENT_EN = Pattern.compile(
            "(?i)(?:last|past)\\s+(\\d+)\\s*(minutes?|hours?|days?)");
    private static final Pattern FROM_TODAY = Pattern.compile(
            "从?今天\\s*(\\d{1,2}):(\\d{2})\\s*(?:开始|起)?");
    private static final Pattern ISO_OFFSET_DATE_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?"
                    + "(?:Z|[+-]\\d{2}:\\d{2})");

    @Override
    public TimeResolution resolve(String expression, Instant now, ZoneId zoneId,
                                  TimeWindowPolicy policy) {
        if (expression == null || expression.isBlank()) {
            return TimeResolution.unresolved("TIME_EXPRESSION_MISSING");
        }
        Optional<TimeWindow> window = relative(expression, now)
                .or(() -> fromToday(expression, now, zoneId))
                .or(() -> calendar(expression, now, zoneId))
                .or(() -> isoRange(expression));
        if (window.isEmpty()) {
            return TimeResolution.unresolved("TIME_EXPRESSION_UNSUPPORTED");
        }
        return policy.violation(window.get(), now)
                .map(TimeResolution::unresolved)
                .orElseGet(() -> TimeResolution.resolved(window.get()));
    }

    private static Optional<TimeWindow> relative(String expression, Instant now) {
        Matcher zh = RECENT_ZH.matcher(expression);
        if (zh.find()) {
            return recentWindow(chineseNumber(zh.group(1)), zh.group(2), now);
        }
        Matcher en = RECENT_EN.matcher(expression);
        if (en.find()) {
            return recentWindow(Integer.parseInt(en.group(1)), en.group(2), now);
        }
        return Optional.empty();
    }

    private static Optional<TimeWindow> recentWindow(int amount, String unit, Instant now) {
        if (amount <= 0 || amount > 10_000) {
            return Optional.empty();
        }
        String normalized = unit.toLowerCase(Locale.ROOT);
        Duration duration = normalized.startsWith("分") || normalized.startsWith("minute")
                ? Duration.ofMinutes(amount)
                : normalized.startsWith("小") || normalized.startsWith("hour")
                ? Duration.ofHours(amount) : Duration.ofDays(amount);
        return Optional.of(new TimeWindow(now.minus(duration), now));
    }

    private static Optional<TimeWindow> calendar(String expression, Instant now, ZoneId zoneId) {
        ZonedDateTime zonedNow = now.atZone(zoneId);
        LocalDate date;
        if (expression.contains("昨天") || expression.toLowerCase(Locale.ROOT).contains("yesterday")) {
            date = zonedNow.toLocalDate().minusDays(1);
        } else if (expression.contains("今天") || expression.toLowerCase(Locale.ROOT).contains("today")) {
            date = zonedNow.toLocalDate();
        } else {
            return Optional.empty();
        }
        Instant start = date.atStartOfDay(zoneId).toInstant();
        Instant end = date.equals(zonedNow.toLocalDate())
                ? now : date.plusDays(1).atStartOfDay(zoneId).toInstant();
        return start.isBefore(end)
                ? Optional.of(new TimeWindow(start, end)) : Optional.empty();
    }

    private static Optional<TimeWindow> fromToday(String expression, Instant now, ZoneId zoneId) {
        Matcher matcher = FROM_TODAY.matcher(expression);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            LocalTime time = LocalTime.of(
                    Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            Instant start = now.atZone(zoneId).toLocalDate().atTime(time).atZone(zoneId).toInstant();
            return start.isBefore(now) ? Optional.of(new TimeWindow(start, now)) : Optional.empty();
        } catch (RuntimeException invalidTime) {
            return Optional.empty();
        }
    }

    private static Optional<TimeWindow> isoRange(String expression) {
        Matcher matcher = ISO_OFFSET_DATE_TIME.matcher(expression);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            Instant start = OffsetDateTime.parse(matcher.group()).toInstant();
            if (!matcher.find()) {
                return Optional.empty();
            }
            Instant end = OffsetDateTime.parse(matcher.group()).toInstant();
            return Optional.of(new TimeWindow(start, end));
        } catch (RuntimeException invalidRange) {
            return Optional.empty();
        }
    }

    private static int chineseNumber(String value) {
        return switch (value) {
            case "一" -> 1;
            case "两", "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> Integer.parseInt(value);
        };
    }
}
