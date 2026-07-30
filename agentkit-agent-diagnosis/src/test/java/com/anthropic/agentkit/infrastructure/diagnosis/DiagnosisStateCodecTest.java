package com.anthropic.agentkit.infrastructure.diagnosis;

import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisScope;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisStep;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentRef;
import com.anthropic.agentkit.domain.diagnosis.Hypothesis;
import com.anthropic.agentkit.domain.diagnosis.StepStatus;
import com.anthropic.agentkit.domain.diagnosis.TimeWindow;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisStateCodecTest {

    private final DiagnosisStateCodec codec = new DiagnosisStateCodec();

    @Test
    void roundTripsDiagnosisStateSnapshot() {
        DiagnosisCase diagnosisCase = DiagnosisCase.open("case-1", "订单失败");
        diagnosisCase.adoptPlan(new DiagnosisPlan(
                "订单失败",
                List.of(Hypothesis.open("H1", "入口服务报错", 0.4)),
                List.of(new DiagnosisStep("S1", "查日志", "H1", List.of("LogQuery"), StepStatus.RUNNING, "")),
                List.of(), scope()));
        diagnosisCase.recordToolEvidence(
                new ToolUseRequest(new ToolUseId("tu-1"), "LogQuery", "{}"),
                ToolResult.ok("inventory fail"));

        String snapshot = codec.encode(diagnosisCase);
        DiagnosisCase restored = codec.decode(snapshot).orElseThrow();

        assertThat(restored.caseId()).isEqualTo("case-1");
        assertThat(restored.plan().problemStatement()).isEqualTo("订单失败");
        assertThat(restored.ledger().all()).hasSize(1);
        assertThat(restored.ledger().all().get(0).toolUseId()).isEqualTo("tu-1");
        assertThat(restored.plan().scope()).isEqualTo(scope());
        assertThat(snapshot)
                .contains("\"schemaVersion\":2")
                .contains("\"startInclusive\":\"2026-07-30T00:00:00Z\"")
                .contains("\"endExclusive\":\"2026-07-30T02:00:00Z\"");
    }

    @Test
    void readsVersionTwoSnapshotWithLegacyNumericInstants() {
        String legacy = """
                {"schemaVersion":2,"caseId":"legacy-v2","question":"errors","status":"RUNNING",
                 "plan":{"problemStatement":"errors","hypotheses":[],"steps":[],"missingInputs":[],
                   "scope":{"environment":{"name":"test"},"services":["agent-web"],
                     "timeWindow":{"startInclusive":1785369600,"endExclusive":1785376800},
                     "identifiers":{},"tags":{}},
                   "blockers":[],"capabilityGeneration":1,"resourceGeneration":1},
                 "evidence":[]}
                """;

        DiagnosisCase restored = codec.decode(legacy).orElseThrow();

        assertThat(restored.plan().scope().timeWindow().startInclusive())
                .isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
        assertThat(restored.plan().scope().timeWindow().endExclusive())
                .isEqualTo(Instant.parse("2026-07-30T02:00:00Z"));
        assertThat(codec.encode(restored))
                .contains("\"startInclusive\":\"2026-07-30T00:00:00Z\"")
                .contains("\"endExclusive\":\"2026-07-30T02:00:00Z\"");
    }

    @Test
    void readsVersionOneSnapshotWithUnknownScope() {
        String v1 = """
                {"schemaVersion":1,"caseId":"legacy","question":"errors","status":"RUNNING",
                 "plan":{"problemStatement":"errors","hypotheses":[],"steps":[],"missingInputs":[]},
                 "evidence":[]}
                """;

        DiagnosisCase restored = codec.decode(v1).orElseThrow();

        assertThat(restored.plan().scope().isKnown()).isFalse();
    }

    @Test
    void returnsEmptyForInvalidSnapshot() {
        assertThat(codec.decode("{broken")).isEmpty();
        assertThat(codec.decode("{\"schemaVersion\":999}")).isEmpty();
    }

    @Test
    void sanitizesEverySecretBearingFieldWhenRestoringAndReencodingLegacyState() {
        String marker = "sk-state-roundtrip-marker";
        String raw = """
                {"schemaVersion":2,
                 "caseId":"case token=state-marker",
                 "question":"investigate Bearer state-marker",
                 "status":"RUNNING",
                 "plan":{
                   "problemStatement":"password=state-marker",
                   "hypotheses":[{
                     "id":"H-sk-state-roundtrip-marker",
                     "statement":"api_key=state-marker",
                     "confidence":0.5,
                     "status":"OPEN",
                     "supportingEvidenceIds":[],
                     "contradictingEvidenceIds":[]}],
                   "steps":[{
                     "id":"S-sk-state-roundtrip-marker",
                     "goal":"Authorization: Basic state-marker",
                     "hypothesisId":"H-sk-state-roundtrip-marker",
                     "allowedTools":["LogQuery-sk-state-roundtrip-marker"],
                     "status":"RUNNING",
                     "resultSummary":"credential=state-marker"}],
                   "missingInputs":["secret=state-marker"],
                   "scope":{
                     "environment":{"name":"env-sk-state-roundtrip-marker"},
                     "services":["service-sk-state-roundtrip-marker"],
                     "timeWindow":{"startInclusive":"2026-07-30T00:00:00Z",
                                   "endExclusive":"2026-07-30T02:00:00Z"},
                     "identifiers":{"traceId":"Bearer state-marker",
                                    "apiKey":"state-marker"},
                     "tags":{"region":"token=state-marker"}},
                   "blockers":[{
                     "type":"BACKEND_UNHEALTHY",
                     "code":"CODE-sk-state-roundtrip-marker",
                     "message":"password=state-marker",
                     "remediation":"Bearer state-marker",
                     "userActionable":false}],
                   "capabilityGeneration":1,
                   "resourceGeneration":2},
                 "evidence":[{
                   "id":"E-sk-state-roundtrip-marker",
                   "source":"TOOL_RESULT",
                   "summary":"api_key=state-marker",
                   "rawExcerpt":"Bearer state-marker",
                   "toolName":"LogQuery-sk-state-roundtrip-marker",
                   "toolUseId":"tu-sk-state-roundtrip-marker",
                   "metadata":{"description":{"nested":"secret=state-marker"},
                               "Authorization":"Bearer state-marker"},
                   "observedAt":"2026-07-30T01:00:00Z",
                   "recordedAt":"2026-07-30T01:00:01Z"}]}
                """;

        DiagnosisCase restored = codec.decode(raw).orElseThrow();
        String safeSnapshot = codec.encode(restored);

        assertThat(restored.question()).isEqualTo("***");
        assertThat(restored.plan().scope().services()).containsExactly("***");
        assertThat(restored.plan().scope().identifiers()).doesNotContainKey("apiKey");
        assertThat(safeSnapshot)
                .doesNotContain(marker, "state-marker", "Bearer", "api_key=", "password=")
                .contains("***");
    }

    private static DiagnosisScope scope() {
        return new DiagnosisScope(
                EnvironmentRef.named("test"), Set.of("agent-web"),
                new TimeWindow(Instant.parse("2026-07-30T00:00:00Z"),
                        Instant.parse("2026-07-30T02:00:00Z")), Map.of(), Map.of());
    }
}
