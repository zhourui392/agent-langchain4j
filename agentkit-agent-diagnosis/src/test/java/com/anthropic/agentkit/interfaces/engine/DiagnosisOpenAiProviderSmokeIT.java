package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.application.diagnosis.PlanGuardMode;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.diagnosis.DataSourceBinding;
import com.anthropic.agentkit.domain.diagnosis.DataSourceType;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalogSnapshot;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisToolMetadata;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentContext;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentRef;
import com.anthropic.agentkit.domain.diagnosis.Evidence;
import com.anthropic.agentkit.domain.diagnosis.OperationalContext;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.anthropic.agentkit.domain.diagnosis.ServiceRef;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.infrastructure.config.AppConfig;
import com.anthropic.agentkit.infrastructure.config.ConfigLoader;
import com.anthropic.agentkit.infrastructure.config.LlmProvider;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisStateCodec;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisToolBackends;
import com.anthropic.agentkit.infrastructure.llm.LlmClientFactories;
import com.anthropic.agentkit.infrastructure.tools.support.LocalFileLogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.LocalLogSource;
import com.anthropic.agentkit.infrastructure.tools.support.ScopedLogQueryClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real OpenAI-compatible release smoke for the complete diagnosis tool/evidence chain.
 *
 * @author alex
 */
@EnabledIfEnvironmentVariable(named = "AK_API_KEY", matches = ".+")
class DiagnosisOpenAiProviderSmokeIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path logs;

    @Test
    void providerPlansExecutesLocalLogAndReportsEvidenceWithoutLeakingCredential()
            throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Files.writeString(logs.resolve("agent-web-smoke.log"),
                now.minus(Duration.ofMinutes(20))
                        + " ERROR smoke-diagnosis-marker NullPointerException "
                        + "at SmokeFixture.execute(SmokeFixture.java:42)\n",
                StandardCharsets.UTF_8);
        String key = System.getenv("AK_API_KEY");
        AtomicReference<RunSummary> completed = new AtomicReference<>();
        List<String> events = new ArrayList<>();

        try (DiagnoseEngine engine = engine()) {
            engine.run(request(now), events::add, completed::set);
        }

        RunSummary summary = completed.get();
        assertThat(summary).isNotNull();
        assertThat(summary.reason()).isEqualTo(ExitReason.SUCCESS);
        DiagnosisCase state = new DiagnosisStateCodec().decode(summary.stateSnapshot())
                .orElseThrow();
        Evidence evidence = state.ledger().all().stream()
                .filter(item -> "LogQuery".equals(item.toolName()))
                .findFirst().orElseThrow();
        JsonNode plan = event(events, "diagnosis_plan").path("payload").path("plan");

        assertThat(plan.path("steps").isEmpty()).isFalse();
        assertThat(events.stream().anyMatch(this::isLogQueryUse)).isTrue();
        assertThat(events.stream().anyMatch(this::isToolResult)).isTrue();
        assertThat(events.stream().anyMatch(line -> type(line).equals("diagnosis_report"))).isTrue();
        assertThat(evidence.toolUseId()).isNotBlank();
        assertThat(evidence.rawExcerpt()).contains("smoke-diagnosis-marker");
        assertThat(evidence.metadata())
                .containsEntry(DiagnosisToolMetadata.DATA_SOURCE_ID, "smoke-local-logs")
                .containsEntry(DiagnosisToolMetadata.ENVIRONMENT, "test")
                .containsEntry(DiagnosisToolMetadata.QUERY_START,
                        now.minus(Duration.ofHours(2)).toString())
                .containsEntry(DiagnosisToolMetadata.QUERY_END, now.toString());
        String exported = String.join("\n", events) + summary.stateSnapshot();
        assertThat(exported.contains(key)).as("provider credential is absent from stream/state")
                .isFalse();
    }

    private DiagnoseEngine engine() {
        AppConfig provider = new AppConfig(
                System.getenv("AK_API_KEY"), model(), ConfigLoader.DEFAULT_MAX_TOKENS,
                baseUrl(), PermissionMode.DEFAULT, LlmProvider.OPENAI);
        LocalLogSource source = new LocalLogSource(
                "smoke-local-logs", logs.toAbsolutePath(), Set.of("*.log"),
                ZoneId.of("UTC"), 2, 1000, 1024 * 1024L, 2, Duration.ofSeconds(2));
        var local = new ScopedLogQueryClient(
                new LocalFileLogQueryClient(source), "smoke-local-logs", "test", "agent-web");
        ServiceRef service = new ServiceRef("agent-web", Set.of("web"));
        var binding = new DataSourceBinding(
                EnvironmentRef.named("test"), service, "smoke-local-logs", DataSourceType.LOG,
                "LogQuery", ReadinessStatus.READY, true, Set.of("query"), Map.of("kind", "local"));
        var resources = new DiagnosisResourceCatalogSnapshot(
                1L, List.of(service), List.of(binding));
        return DiagnoseEngineBuilder.create()
                .llm(LlmClientFactories.create(provider))
                .toolBackends(DiagnosisToolBackends.builder().logQuery(local).build())
                .budget(new AgentBudget(12, 12, 200_000, 20_000, 200_000, 12))
                .mode(DiagnosisMode.OPERATIONAL)
                .planGuardMode(PlanGuardMode.ENFORCE)
                .readinessPolicy(ReadinessPolicy.failFast())
                .resourceCatalog(() -> resources)
                .structuredDiagnosis()
                .build();
    }

    private RunRequest request(Instant now) {
        return RunRequest.builder()
                .sessionId("diagnosis-provider-smoke")
                .workingDir(logs.toAbsolutePath().toString())
                .userMessage("请直接诊断 agent-web 最近两小时的 ERROR 日志，必须调用 LogQuery；"
                        + "根据证据说明 smoke-diagnosis-marker 的原因，不要询问日志平台、环境、服务或时区。")
                .operationalContext(new OperationalContext(
                        now, ZoneId.of("UTC"), EnvironmentContext.named("test"),
                        "agent-web", List.of(), Map.of()))
                .timeoutSeconds(300)
                .build();
    }

    private boolean isLogQueryUse(String line) {
        JsonNode node = parse(line);
        if (!"assistant".equals(node.path("type").asText())) {
            return false;
        }
        return node.at("/message/content").findValuesAsText("name").contains("LogQuery");
    }

    private boolean isToolResult(String line) {
        JsonNode node = parse(line);
        return "user".equals(node.path("type").asText())
                && node.at("/message/content").findValuesAsText("type").contains("tool_result");
    }

    private static JsonNode event(List<String> events, String expectedType) {
        return events.stream().map(DiagnosisOpenAiProviderSmokeIT::parse)
                .filter(node -> expectedType.equals(node.path("type").asText()))
                .findFirst().orElseThrow();
    }

    private static String type(String line) {
        return parse(line).path("type").asText();
    }

    private static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("invalid stream event", failure);
        }
    }

    private static String baseUrl() {
        String value = System.getenv("AK_BASE_URL");
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String model() {
        String value = System.getenv("AK_MODEL");
        return value == null || value.isBlank()
                ? ConfigLoader.DEFAULT_OPENAI_MODEL : value.trim();
    }
}
