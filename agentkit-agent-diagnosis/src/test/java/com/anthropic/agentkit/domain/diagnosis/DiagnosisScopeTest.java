package com.anthropic.agentkit.domain.diagnosis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author alex
 */
class DiagnosisScopeTest {

    private static final Instant START = Instant.parse("2026-07-30T00:00:00Z");
    private static final Instant END = Instant.parse("2026-07-30T02:00:00Z");

    @Test
    void rejectsInvalidTimeWindow() {
        assertThatThrownBy(() -> new TimeWindow(END, START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startInclusive");
        assertThatThrownBy(() -> new TimeWindow(START, START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startInclusive");
    }

    @Test
    void onlyAllowsScopeSubsets() {
        DiagnosisScope scope = new DiagnosisScope(
                EnvironmentRef.named("test"), Set.of("agent-web"),
                new TimeWindow(START, END), Map.of("traceId", "trace-1"), Map.of());

        assertThat(scope.contains(EnvironmentRef.named("test"), Set.of("agent-web"),
                new TimeWindow(START.plusSeconds(60), END))).isTrue();
        assertThat(scope.contains(EnvironmentRef.named("prod"), Set.of("agent-web"),
                new TimeWindow(START, END))).isFalse();
        assertThat(scope.contains(EnvironmentRef.named("test"), Set.of("payment"),
                new TimeWindow(START, END))).isFalse();
        assertThat(scope.contains(EnvironmentRef.named("test"), Set.of("agent-web"),
                new TimeWindow(START.minusSeconds(1), END))).isFalse();
    }

    @Test
    void unknownScopeIsExplicitAndDoesNotConstrainLegacySnapshots() {
        DiagnosisScope scope = DiagnosisScope.unknown();

        assertThat(scope.isKnown()).isFalse();
        assertThat(scope.contains(EnvironmentRef.named("test"), Set.of("agent-web"),
                new TimeWindow(START, END))).isTrue();
    }

    @Test
    void removesSecretBearingKeysAndValuesBeforeScopeCanEnterStateOrPrompt() {
        DiagnosisScope scope = new DiagnosisScope(
                EnvironmentRef.named("env-sk-scope-marker"),
                Set.of("agent-web", "service-sk-scope-marker"),
                new TimeWindow(START, END),
                Map.of("traceId", "Bearer must-not-survive",
                        "apiKey", "must-not-survive-either"),
                Map.of("region", "api_key=must-not-survive"));

        assertThat(scope.identifiers()).doesNotContainKey("apiKey");
        assertThat(scope.environment().name()).isEqualTo("***");
        assertThat(scope.services()).contains("agent-web", "***");
        assertThat(scope.identifiers()).containsEntry("traceId", "***");
        assertThat(scope.tags()).containsEntry("region", "***");
        assertThat(scope.toString()).doesNotContain(
                "must-not-survive", "sk-scope-marker", "Bearer", "api_key=");
    }
}
