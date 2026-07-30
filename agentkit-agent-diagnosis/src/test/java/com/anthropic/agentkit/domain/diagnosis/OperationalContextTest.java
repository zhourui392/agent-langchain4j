package com.anthropic.agentkit.domain.diagnosis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author alex
 */
class OperationalContextTest {

    @Test
    void rejectsSecretLikeAttributes() {
        assertThatThrownBy(() -> new OperationalContext(
                Instant.EPOCH, ZoneId.of("UTC"), EnvironmentContext.unknown(), "",
                List.of(), Map.of("apiKey", "must-not-enter-context")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sensitive attribute");

        assertThatThrownBy(() -> new OperationalContext(
                Instant.EPOCH, ZoneId.of("UTC"), EnvironmentContext.unknown(), "",
                List.of(), Map.of("authorization.header", "must-not-enter-context")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sensitive attribute");
    }

    @Test
    void redactsSecretBearingAttributeValues() {
        OperationalContext context = new OperationalContext(
                Instant.EPOCH, ZoneId.of("UTC"), EnvironmentContext.unknown(), "",
                List.of(), Map.of("region", "Bearer must-not-survive"));

        assertThat(context.attributes()).containsEntry("region", "***");
        assertThat(context.toString()).doesNotContain("must-not-survive", "Bearer");
    }

    @Test
    void redactsSecretBearingEnvironmentServiceAndCapabilityValues() {
        OperationalContext context = new OperationalContext(
                Instant.EPOCH, ZoneId.of("UTC"),
                new EnvironmentContext("env-sk-context-marker", "Bearer context-marker",
                        "token=context-marker"),
                "service-sk-context-marker",
                List.of(new DataSourceView(
                        "logs-sk-context-marker", DataSourceType.LOG, ReadinessStatus.READY,
                        Set.of("query-sk-context-marker"))),
                Map.of());

        assertThat(context.environment().name()).isEqualTo("***");
        assertThat(context.defaultService()).isEqualTo("***");
        assertThat(context.dataSources().getFirst().id()).isEqualTo("***");
        assertThat(context.toString())
                .doesNotContain("sk-context-marker", "context-marker", "Bearer", "token=");
    }

    @Test
    void enrichesHostContextFromOneImmutableResourceGeneration() {
        ServiceRef service = new ServiceRef("agent-web", Set.of("web"));
        DiagnosisResourceCatalogSnapshot resources = new DiagnosisResourceCatalogSnapshot(
                12,
                List.of(service),
                List.of(new DataSourceBinding(
                        EnvironmentRef.named("test"), service, "local-agent-web-logs",
                        DataSourceType.LOG, "LogQuery", ReadinessStatus.READY, true,
                        Set.of("query"), Map.of("logFormat", "spring-boot"))));
        OperationalContext context = new OperationalContext(
                Instant.parse("2026-07-30T02:00:00Z"), ZoneId.of("UTC"),
                EnvironmentContext.named("test"), "", List.of(), Map.of());

        OperationalContext enriched = context.withResources(resources);

        assertThat(enriched.defaultService()).isEqualTo("agent-web");
        assertThat(enriched.serviceResolution().status())
                .isEqualTo(ServiceResolutionStatus.RESOLVED);
        assertThat(enriched.resourceGeneration()).isEqualTo(12);
        assertThat(enriched.dataSources()).extracting(DataSourceView::id)
                .containsExactly("local-agent-web-logs");
    }
}
