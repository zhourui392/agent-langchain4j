package com.anthropic.agentkit.domain.diagnosis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author alex
 */
class DiagnosisResourceCatalogSnapshotTest {

    private static final EnvironmentRef TEST = EnvironmentRef.named("test");
    private static final EnvironmentRef PROD = EnvironmentRef.named("prod");
    private static final ServiceRef AGENT_WEB = new ServiceRef(
            "agent-web", Set.of("web", "诊断平台"));
    private static final ServiceRef ORDER = new ServiceRef(
            "order-service", Set.of("order", "订单服务"));

    @Test
    void resolvesExplicitAliasBeforeHostDefaultAndKeepsEnvironmentBoundary() {
        DiagnosisResourceCatalogSnapshot snapshot = snapshot();

        ServiceResolution resolution = snapshot.resolveService(
                TEST, new ServiceSelection("订单服务", "agent-web", "agent-web"));

        assertThat(resolution.status()).isEqualTo(ServiceResolutionStatus.RESOLVED);
        assertThat(resolution.resolvedService()).contains(ORDER);
        assertThat(snapshot.dataSourcesFor(TEST, ORDER))
                .extracting(DataSourceBinding::dataSourceId)
                .containsExactly("local-order-logs");
        assertThat(snapshot.dataSourcesFor(PROD, ORDER)).isEmpty();
    }

    @Test
    void usesSelectedThenDefaultThenUniqueVisibleService() {
        DiagnosisResourceCatalogSnapshot snapshot = snapshot();

        assertThat(snapshot.resolveService(
                TEST, new ServiceSelection("", "web", "order")).resolvedService())
                .contains(AGENT_WEB);
        assertThat(snapshot.resolveService(
                TEST, new ServiceSelection("", "", "order")).resolvedService())
                .contains(ORDER);

        DiagnosisResourceCatalogSnapshot single = new DiagnosisResourceCatalogSnapshot(
                8, List.of(AGENT_WEB), List.of(binding(TEST, AGENT_WEB, "local-web-logs")));
        assertThat(single.resolveService(TEST, ServiceSelection.empty()).resolvedService())
                .contains(AGENT_WEB);
    }

    @Test
    void reportsAmbiguousAndUnknownWithoutGuessing() {
        DiagnosisResourceCatalogSnapshot snapshot = snapshot();

        ServiceResolution ambiguous = snapshot.resolveService(TEST, ServiceSelection.empty());
        ServiceResolution unknown = snapshot.resolveService(
                TEST, new ServiceSelection("missing", "", ""));

        assertThat(ambiguous.status()).isEqualTo(ServiceResolutionStatus.AMBIGUOUS);
        assertThat(ambiguous.candidates()).containsExactly(AGENT_WEB, ORDER);
        assertThat(unknown.status()).isEqualTo(ServiceResolutionStatus.UNKNOWN);
        assertThat(unknown.resolvedService()).isEmpty();
        assertThat(unknown.candidates()).containsExactly(AGENT_WEB, ORDER);
    }

    @Test
    void preservesGenerationAndRejectsSecretLikeTagsOrAliasCollision() {
        DiagnosisResourceCatalogSnapshot snapshot = snapshot();

        assertThat(snapshot.generation()).isEqualTo(42);
        assertThat(snapshot.dataSourcesFor(TEST, AGENT_WEB).getFirst().tags())
                .containsEntry("logFormat", "spring-boot")
                .doesNotContainKeys("endpoint", "authorization");
        assertThatThrownBy(() -> new DataSourceBinding(
                TEST, AGENT_WEB, "logs", DataSourceType.LOG, "LogQuery",
                ReadinessStatus.READY, true, Set.of("query"),
                Map.of("authorization", "must-not-exist")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sensitive");
        assertThatThrownBy(() -> new DiagnosisResourceCatalogSnapshot(
                1,
                List.of(AGENT_WEB, new ServiceRef("another", Set.of("web"))),
                List.of(binding(TEST, AGENT_WEB, "logs"),
                        binding(TEST, new ServiceRef("another", Set.of("web")), "other-logs"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias");
    }

    @Test
    void repeatedSafeWordsInTagKey_shouldNotCauseDuplicateSetFailure() {
        DataSourceBinding binding = new DataSourceBinding(
                TEST, AGENT_WEB, "logs", DataSourceType.LOG, "LogQuery",
                ReadinessStatus.READY, true, Set.of("query"),
                Map.of("logLog", "spring-boot"));

        assertThat(binding.tags()).containsEntry("logLog", "spring-boot");
    }

    @Test
    void redactsSecretBearingTagValues() {
        DataSourceBinding binding = new DataSourceBinding(
                TEST, AGENT_WEB, "logs", DataSourceType.LOG, "LogQuery",
                ReadinessStatus.READY, true, Set.of("query"),
                Map.of("description", "token=must-not-survive"));

        assertThat(binding.tags()).containsEntry("description", "***");
        assertThat(binding.toString()).doesNotContain("must-not-survive", "token=");
    }

    private static DiagnosisResourceCatalogSnapshot snapshot() {
        return new DiagnosisResourceCatalogSnapshot(
                42,
                List.of(ORDER, AGENT_WEB),
                List.of(
                        binding(TEST, AGENT_WEB, "local-web-logs"),
                        binding(TEST, ORDER, "local-order-logs"),
                        binding(PROD, AGENT_WEB, "prod-web-logs")));
    }

    private static DataSourceBinding binding(
            EnvironmentRef environment, ServiceRef service, String dataSourceId) {
        return new DataSourceBinding(
                environment, service, dataSourceId, DataSourceType.LOG, "LogQuery",
                ReadinessStatus.READY, true, Set.of("query"),
                Map.of("logFormat", "spring-boot"));
    }
}
