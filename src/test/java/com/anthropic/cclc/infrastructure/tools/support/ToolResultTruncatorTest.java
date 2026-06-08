package com.anthropic.cclc.infrastructure.tools.support;

import com.anthropic.cclc.domain.conversation.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTruncatorTest {

    private final ToolResultTruncator truncator = ToolResultTruncator.withDefaults();

    @Test
    void keepsSmallResultIntact() {
        String content = "line1\nline2\nline3";

        assertThat(truncator.truncate(content)).isEqualTo(content);
    }

    @Test
    void truncatesLargeResultWithMarker() {
        String content = IntStream.range(0, 500)
                .mapToObj(i -> "log line " + i)
                .collect(Collectors.joining("\n"));

        String result = truncator.truncate(content);

        assertThat(result).isNotEqualTo(content);
        assertThat(result).contains("lines").contains("omitted");
        assertThat(result.lines().count()).isLessThan(500L);
        assertThat(result).startsWith("log line 0");
        assertThat(result).contains("log line 499");
        assertThat(result).doesNotContain("log line 250");
    }

    @Test
    void truncatesByTokenEstimate() {
        String hugeOneLine = "x".repeat(100_000);

        String result = truncator.truncate(hugeOneLine);

        assertThat(result).isNotEqualTo(hugeOneLine);
        assertThat(result.length()).isLessThan(hugeOneLine.length());
        assertThat(result).containsIgnoringCase("tokens omitted");
    }

    @Test
    void thresholdsAreConfigurable() {
        ToolResultTruncator strict = new ToolResultTruncator(3, 1, 1, 10, TokenEstimator.CHAR_HEURISTIC);
        String content = "a\nb\nc\nd\ne";

        String result = strict.truncate(content);

        assertThat(result).contains("omitted");
        assertThat(result).startsWith("a");
        assertThat(result).endsWith("e");
    }
}
