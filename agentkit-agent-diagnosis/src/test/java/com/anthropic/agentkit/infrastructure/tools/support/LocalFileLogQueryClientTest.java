package com.anthropic.agentkit.infrastructure.tools.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author alex
 */
class LocalFileLogQueryClientTest {

    @TempDir
    Path root;

    @Test
    void filtersByAbsoluteWindowAndKeepsStackTraceTogether() throws Exception {
        Files.writeString(root.resolve("agent-web.log"), """
                2026-07-30T00:30:00Z INFO started
                2026-07-30T01:15:00Z ERROR request failed trace-1
                java.lang.NullPointerException: boom
                    at example.Service.call(Service.java:10)
                2026-07-30T02:30:00Z ERROR outside window
                """);
        LocalFileLogQueryClient client = client(source(20, 100_000));

        String result = client.query(new LogQueryRequest(
                "trace-1", "ERROR", "agent-web", "2026-07-30T01:00:00Z",
                "2026-07-30T02:00:00Z", "ERROR", 10));

        assertThat(result).contains(
                "dataSourceId=local-agent-web-logs", "matched=1", "agent-web.log",
                "request failed", "NullPointerException", "Service.java:10");
        assertThat(result).doesNotContain("outside window", root.toString());
    }

    @Test
    void ignoresSensitiveFilesAndRejectsSymlinkEscape() throws Exception {
        Files.writeString(root.resolve("token-secret.log"),
                "2026-07-30T01:00:00Z ERROR must-not-leak");
        Path outside = Files.createTempFile("outside-log", ".log");
        Files.writeString(outside, "2026-07-30T01:00:00Z ERROR outside-secret");
        try {
            Files.createSymbolicLink(root.resolve("linked.log"), outside);
        } catch (UnsupportedOperationException ignored) {
            return;
        }

        String result = client(source(20, 100_000)).query(request());

        assertThat(result).doesNotContain("must-not-leak", "outside-secret", outside.toString());
    }

    @Test
    void enforcesLineAndByteLimitsAndRedactsSecrets() throws Exception {
        Files.writeString(root.resolve("agent-web.log"), """
                2026-07-30T01:00:00Z ERROR first
                2026-07-30T01:01:00Z ERROR second
                2026-07-30T01:02:00Z ERROR api_key=super-secret-value third
                """);

        String result = client(source(2, 160)).query(request());

        assertThat(result).contains("truncated=true", "api_key=***");
        assertThat(result).doesNotContain("super-secret-value", "first");
    }

    @Test
    void validatesHostConfiguredRootAndGlob() {
        assertThatThrownBy(() -> new LocalLogSource(
                "logs", Path.of("relative"), Set.of("*.log"), ZoneId.of("UTC"),
                2, 20, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> new LocalLogSource(
                "logs", root, Set.of("../*.log"), ZoneId.of("UTC"),
                2, 20, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("glob");
    }

    @Test
    void reportsRequestAndFileLimitTruncationWithoutStoppingAtFirstMatch() throws Exception {
        Files.writeString(root.resolve("a.log"), """
                2026-07-30T01:00:00Z ERROR first
                2026-07-30T01:01:00Z ERROR second
                """);
        Files.writeString(root.resolve("b.log"),
                "2026-07-30T01:02:00Z ERROR third\n");
        LocalLogSource source = new LocalLogSource(
                "local-agent-web-logs", root, Set.of("*.log"), ZoneId.of("UTC"),
                1, 20, 100_000);

        String result = client(source).query(new LogQueryRequest(
                "", "ERROR", "agent-web", "2026-07-30T00:00:00Z",
                "2026-07-30T02:00:00Z", "ERROR", 1));

        assertThat(result).contains("matched=2", "returned=1", "truncated=true");
    }

    @Test
    void stopsWhenHostScanDeadlineExpires() throws Exception {
        Files.writeString(root.resolve("agent-web.log"),
                "2026-07-30T01:00:00Z ERROR should-not-be-read\n");
        AtomicLong ticker = new AtomicLong();
        LocalLogSource source = new LocalLogSource(
                "local-agent-web-logs", root, Set.of("*.log"), ZoneId.of("UTC"),
                10, 20, 100_000, 4, java.time.Duration.ofNanos(2));
        LocalFileLogQueryClient client = new LocalFileLogQueryClient(
                source, Clock.fixed(Instant.parse("2026-07-30T02:00:00Z"), ZoneOffset.UTC),
                () -> ticker.getAndIncrement());

        String result = client.query(request());

        assertThat(result).contains("matched=0", "truncated=true");
        assertThat(result).doesNotContain("should-not-be-read");
    }

    @Test
    void scansTheRecentTailWithinTheGlobalBudget() throws Exception {
        StringBuilder busyLog = new StringBuilder();
        for (int index = 0; index < 20; index++) {
            busyLog.append("2026-07-30T01:00:")
                    .append(String.format("%02d", index))
                    .append("Z INFO unrelated application event\n");
        }
        busyLog.append("2026-07-30T01:30:00Z ERROR NATIVE_SMOKE_TARGET request failed\n")
                .append("java.lang.NullPointerException: controlled fixture\n");
        Files.writeString(root.resolve("application.log"), busyLog);
        LocalLogSource source = new LocalLogSource(
                "local-agent-web-logs", root, Set.of("*.log"), ZoneId.of("UTC"),
                10, 6, 1_000);

        String result = client(source).query(new LogQueryRequest(
                "", "NATIVE_SMOKE_TARGET", "agent-web", "2026-07-30T00:00:00Z",
                "2026-07-30T02:00:00Z", "ERROR", 10));

        assertThat(result).contains(
                "matched=1", "application.log", "NullPointerException");
    }

    @Test
    void sharesTheGlobalBudgetAcrossCandidateFiles() throws Exception {
        StringBuilder busyLog = new StringBuilder();
        for (int index = 0; index < 20; index++) {
            busyLog.append("2026-07-30T01:00:")
                    .append(String.format("%02d", index))
                    .append("Z INFO unrelated application event\n");
        }
        busyLog.append("2026-07-30T01:30:00Z ERROR SHARED_TARGET busy-tail\n");
        Files.writeString(root.resolve("a-application.log"), busyLog);
        Files.writeString(root.resolve("b-fixture.log"),
                "2026-07-30T01:31:00Z ERROR SHARED_TARGET controlled-fixture\n");
        LocalLogSource source = new LocalLogSource(
                "local-agent-web-logs", root, Set.of("*.log"), ZoneId.of("UTC"),
                10, 6, 1_000);

        String result = client(source).query(new LogQueryRequest(
                "", "SHARED_TARGET", "agent-web", "2026-07-30T00:00:00Z",
                "2026-07-30T02:00:00Z", "ERROR", 10));

        assertThat(result).contains(
                "matched=2", "a-application.log", "busy-tail",
                "b-fixture.log", "controlled-fixture");
    }

    @Test
    void selectsBoundedCandidatesInDeterministicLogicalNameOrder() throws Exception {
        Files.writeString(root.resolve("z-last.log"),
                "2026-07-30T01:00:00Z ERROR TARGET should-not-win\n");
        Files.writeString(root.resolve("a-first.log"),
                "2026-07-30T01:00:00Z ERROR TARGET deterministic-winner\n");
        LocalLogSource source = new LocalLogSource(
                "local-agent-web-logs", root, Set.of("*.log"), ZoneId.of("UTC"),
                1, 20, 10_000);

        String result = client(source).query(new LogQueryRequest(
                "", "TARGET", "agent-web", "2026-07-30T00:00:00Z",
                "2026-07-30T02:00:00Z", "ERROR", 10));

        assertThat(result).contains("a-first.log", "deterministic-winner", "truncated=true")
                .doesNotContain("z-last.log", "should-not-win");
    }

    private LocalFileLogQueryClient client(LocalLogSource source) {
        return new LocalFileLogQueryClient(
                source, Clock.fixed(Instant.parse("2026-07-30T02:00:00Z"), ZoneOffset.UTC));
    }

    private LocalLogSource source(int maxLines, long maxBytes) {
        return new LocalLogSource(
                "local-agent-web-logs", root, Set.of("*.log"), ZoneId.of("UTC"),
                10, maxLines, maxBytes);
    }

    private static LogQueryRequest request() {
        return new LogQueryRequest(
                "", "ERROR", "agent-web", "2026-07-30T00:00:00Z",
                "2026-07-30T02:00:00Z", "ERROR", 10);
    }
}
