package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisBlocker;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisBlockerType;
import com.anthropic.agentkit.domain.diagnosis.DataSourceBinding;
import com.anthropic.agentkit.domain.diagnosis.DataSourceType;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisExecutionCapabilities;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalogSnapshot;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisScope;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisReport;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisStep;
import com.anthropic.agentkit.domain.diagnosis.Evidence;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentContext;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentRef;
import com.anthropic.agentkit.domain.diagnosis.Hypothesis;
import com.anthropic.agentkit.domain.diagnosis.OperationalContext;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.anthropic.agentkit.domain.diagnosis.ServiceRef;
import com.anthropic.agentkit.domain.diagnosis.StepStatus;
import com.anthropic.agentkit.domain.diagnosis.TimeWindow;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolCatalogSnapshot;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisStateCodec;
import com.anthropic.agentkit.infrastructure.diagnosis.StructuredDiagnosisPlanner;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisOrchestratorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DiagnosisStateCodec codec = new DiagnosisStateCodec();

    @Test
    void recordsToolEvidenceAndEmitsSnapshotBeforeResult() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(
                        new ToolUseRequest(new ToolUseId("tu-1"), "LogQuery", "{}"))))
                .enqueue(AiMessage.text("done"));
        ToolRegistry tools = new ToolRegistry().register(FakeTool.readOnlyReturning("LogQuery", "3 errors"));
        List<String> lines = new ArrayList<>();

        orchestrator(llm, tools).run(request(""), conversation(), new CancellationToken(), lines::add);

        List<String> types = lines.stream().map(this::typeOf).toList();
        assertThat(types.indexOf("diagnosis_state")).isLessThan(types.indexOf("result"));
        DiagnosisCase restored = decodeSnapshot(lines);
        assertThat(restored.ledger().all()).hasSize(1);
        assertThat(restored.ledger().all().get(0).toolUseId()).isEqualTo("tu-1");
    }

    @Test
    void restoresIncomingSnapshot() {
        DiagnosisCase prior = caseWithEvidence();
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        List<String> lines = new ArrayList<>();

        orchestrator(llm, new ToolRegistry()).run(
                request(codec.encode(prior)), conversation(), new CancellationToken(), lines::add);

        DiagnosisCase restored = decodeSnapshot(lines);
        assertThat(restored.ledger().all()).hasSize(1);
        assertThat(restored.ledger().all().get(0).toolUseId()).isEqualTo("old-tu");
    }

    @Test
    void replansCompletedSnapshotForAFollowUpTurn() {
        DiagnosisCase prior = caseWithEvidence();
        prior.markDone();
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("follow-up done"));
        DiagnosisOrchestrator orchestrator = new DiagnosisOrchestrator(
                llm, new ToolRegistry(), AgentBudget.unlimited(), codec, new FakePlanner());
        List<String> lines = new ArrayList<>();

        OrchestrationResult result = orchestrator.run(
                request(codec.encode(prior)), conversation(), new CancellationToken(), lines::add);

        assertThat(result.agentRunResult().finalMessage().text()).isEqualTo("follow-up done");
        assertThat(decodeSnapshot(lines).status()).isEqualTo(
                com.anthropic.agentkit.domain.diagnosis.DiagnosisStatus.DONE);
    }

    @Test
    void emitsPlanBeforeMainAgentRunWhenPlannerIsConfigured() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        DiagnosisOrchestrator orchestrator = new DiagnosisOrchestrator(
                llm, new ToolRegistry(), AgentBudget.unlimited(), codec, new FakePlanner());
        List<String> lines = new ArrayList<>();

        orchestrator.run(request(""), conversation(), new CancellationToken(), lines::add);

        List<String> types = lines.stream().map(this::typeOf).toList();
        assertThat(types.indexOf("diagnosis_plan")).isLessThan(types.indexOf("result"));
        JsonNode event = lines.stream().map(this::parse)
                .filter(node -> node.path("type").asText().equals("diagnosis_plan"))
                .findFirst().orElseThrow();
        assertThat(event.path("payload").path("plan").path("problemStatement").asText()).isEqualTo("hi");
    }

    @Test
    void emitsNeedInfoAndSkipsMainAgentWhenPlanHasMissingInputs() {
        StubLlmClient llm = new StubLlmClient();
        FakeReporter reporter = new FakeReporter();
        DiagnosisOrchestrator orchestrator = configuredOrchestrator(
                llm, new ToolRegistry(), new MissingInputPlanner(), reporter);
        List<String> lines = new ArrayList<>();

        OrchestrationResult result = orchestrator.run(
                request(""), conversation(), new CancellationToken(), lines::add);

        assertThat(lines.stream().map(this::typeOf).toList())
                .contains("diagnosis_plan", "diagnosis_need_info", "diagnosis_state", "result");
        assertThat(llm.capturedRequests()).isEmpty();
        assertThat(reporter.calls).isZero();
        assertThat(result.agentRunResult().finalMessage().text())
                .contains("traceId", "timeWindow")
                .doesNotContain("Need more information before diagnosis.");
    }

    @Test
    void updatesPlanAfterToolEvidence() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(
                        new ToolUseRequest(new ToolUseId("tu-1"), "LogQuery", "{}"))))
                .enqueue(AiMessage.text("done"));
        ToolRegistry tools = new ToolRegistry().register(FakeTool.readOnlyReturning("LogQuery", "3 errors"));
        UpdatingPlanner planner = new UpdatingPlanner();
        DiagnosisOrchestrator orchestrator = new DiagnosisOrchestrator(
                llm, tools, AgentBudget.unlimited(), codec, planner);
        List<String> lines = new ArrayList<>();

        orchestrator.run(request(""), conversation(), new CancellationToken(), lines::add);

        assertThat(planner.updateCalls).isEqualTo(1);
        DiagnosisCase restored = decodeSnapshot(lines);
        assertThat(restored.plan().problemStatement()).isEqualTo("updated");
    }

    @Test
    void mainAgentReceivesTheResolvedHostApprovedScope() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        DiagnosisPlan scopedPlan = new DiagnosisPlan(
                "bounded log diagnosis",
                List.of(Hypothesis.open("H1", "application errors", 0.5)),
                List.of(new DiagnosisStep("S1", "query logs", "H1",
                        List.of("LogQuery"), StepStatus.PENDING, "")),
                List.of(), new DiagnosisScope(
                EnvironmentRef.named("test"), Set.of("agent-web"),
                new TimeWindow(Instant.parse("2026-07-30T00:00:00Z"),
                        Instant.parse("2026-07-30T02:00:00Z")),
                Map.of("logMarker", "DIA75"), Map.of()), List.of());

        configuredOrchestrator(
                llm, new ToolRegistry(), new FixedPlanPlanner(scopedPlan), null).run(
                request(""), conversation(), new CancellationToken(), ignored -> { });

        assertThat(llm.capturedRequests()).singleElement().satisfies(captured ->
                assertThat(captured.systemPrompt()).contains(
                        "Host-approved diagnosis scope", "environment=test",
                        "services=agent-web", "startTime=2026-07-30T00:00:00Z",
                        "endTime=2026-07-30T02:00:00Z", "LogQuery", "DIA75"));
    }

    @Test
    void blockerProducedByReplanStopsBeforeAnotherModelTurn() {
        StubLlmClient llm = new StubLlmClient().enqueue(new AiMessage("", List.of(
                new ToolUseRequest(new ToolUseId("failed-query"), "LogQuery", "{}"))));
        FakeTool logQuery = FakeTool.readOnlyReturning("LogQuery", "backend unavailable");
        BlockingUpdatePlanner planner = new BlockingUpdatePlanner();
        List<String> lines = new ArrayList<>();

        OrchestrationResult result = configuredOrchestrator(
                llm, new ToolRegistry().register(logQuery), planner, null).run(
                request(""), conversation(), new CancellationToken(), lines::add);

        assertThat(llm.capturedRequests()).hasSize(1);
        assertThat(logQuery.callCount()).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo(DiagnosisOutcome.BACKEND_UNHEALTHY);
        assertThat(result.agentRunResult().finalMessage().text())
                .contains("DIAGNOSIS_BACKEND_UNHEALTHY");
        assertThat(lines.stream().map(this::typeOf)).contains("diagnosis_blocked");
        assertThat(decodeSnapshot(lines).status()).isEqualTo(
                com.anthropic.agentkit.domain.diagnosis.DiagnosisStatus.BLOCKED);
    }

    @Test
    void emitsStructuredReportBeforeStateAndResult() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        DiagnosisOrchestrator orchestrator = configuredOrchestrator(
                llm, new ToolRegistry(), new FakePlanner(), new FakeReporter());
        List<String> lines = new ArrayList<>();

        orchestrator.run(request(""), conversation(), new CancellationToken(), lines::add);

        List<String> types = lines.stream().map(this::typeOf).toList();
        assertThat(types.indexOf("diagnosis_report")).isLessThan(types.indexOf("diagnosis_state"));
        assertThat(types.indexOf("diagnosis_report")).isLessThan(types.indexOf("result"));
    }

    @Test
    void planGuardObservesOffPlanToolWithoutBlocking() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(
                        new ToolUseRequest(new ToolUseId("tu-1"), "RedisRead", "{}"))))
                .enqueue(AiMessage.text("done"));
        FakeTool redis = FakeTool.readOnlyReturning("RedisRead", "cache ok");
        ToolRegistry tools = new ToolRegistry().register(redis);
        DiagnosisOrchestrator orchestrator = configuredOrchestrator(
                llm, tools, new FakePlanner(), null);
        List<String> lines = new ArrayList<>();

        orchestrator.run(request(""), conversation(), new CancellationToken(), lines::add);

        assertThat(redis.callCount()).isEqualTo(1);
        assertThat(lines).noneSatisfy(line -> assertThat(line).contains("permission denied: RedisRead"));
    }

    @Test
    void plannerAndExecutorShareOneFrozenCapabilityGeneration() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(
                        new ToolUseRequest(new ToolUseId("tu-frozen"), "LogQuery", "{}"))))
                .enqueue(AiMessage.text("done"));
        FakeTool logQuery = FakeTool.readOnlyReturning("LogQuery", "error");
        FakeTool refreshed = FakeTool.readOnlyReturning("RefreshedTool", "new");
        AtomicInteger catalogCalls = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry().registerCatalog(ignored -> {
            int generation = catalogCalls.incrementAndGet();
            return generation == 1
                    ? new ToolCatalogSnapshot("dynamic", 1, List.of(logQuery))
                    : new ToolCatalogSnapshot("dynamic", 2, List.of(refreshed));
        });
        CapabilityPlanner planner = new CapabilityPlanner();

        OrchestrationResult result = configuredOrchestrator(llm, tools, planner, null).run(
                request(""), conversation(), new CancellationToken(), ignored -> { });

        assertThat(catalogCalls).hasValue(1);
        assertThat(planner.capabilities.toolNames()).containsExactly("LogQuery");
        assertThat(logQuery.callCount()).isEqualTo(1);
        assertThat(refreshed.callCount()).isZero();
        assertThat(codec.decode(result.stateSnapshot()).orElseThrow()
                .plan().capabilityGeneration()).isEqualTo(planner.capabilities.generation());
    }

    @Test
    void plannerReceivesOneFrozenResourceGenerationAndResolvedDefaultService() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        CapabilityPlanner planner = new CapabilityPlanner();
        AtomicInteger resourceCalls = new AtomicInteger();
        ServiceRef service = new ServiceRef("agent-web", Set.of("web"));
        DiagnosisResourceCatalogSnapshot resources = new DiagnosisResourceCatalogSnapshot(
                19, List.of(service), List.of(new DataSourceBinding(
                EnvironmentRef.named("test"), service, "local-agent-web-logs",
                DataSourceType.LOG, "LogQuery", ReadinessStatus.READY, true,
                Set.of("query"), Map.of())));
        DiagnosisOrchestrator.Options options = new DiagnosisOrchestrator.Options(
                AgentBudget.unlimited(), codec, planner, null,
                com.anthropic.agentkit.application.diagnosis.PlanGuardMode.OBSERVE, "", "",
                () -> {
                    resourceCalls.incrementAndGet();
                    return resources;
                });
        RunRequest resourceRequest = RunRequest.builder()
                .workingDir(System.getProperty("user.dir"))
                .userMessage("check errors")
                .sessionId("s-1")
                .operationalContext(new OperationalContext(
                        Instant.parse("2026-07-30T02:00:00Z"), ZoneId.of("UTC"),
                        EnvironmentContext.named("test"), "", List.of(), Map.of()))
                .build();

        new DiagnosisOrchestrator(llm, new ToolRegistry(), options).run(
                resourceRequest, conversation(), new CancellationToken(), ignored -> { });

        assertThat(resourceCalls).hasValue(1);
        assertThat(planner.capabilities.resources().generation()).isEqualTo(19);
        assertThat(planner.operationalContext.defaultService()).isEqualTo("agent-web");
        assertThat(planner.operationalContext.dataSources()).extracting("id")
                .containsExactly("local-agent-web-logs");
    }

    @Test
    void environmentMismatch_shouldSkipMainExecutorAndEvidenceTools() {
        StubLlmClient llm = new StubLlmClient().enqueue(new AiMessage("", List.of(
                new ToolUseRequest(new ToolUseId("plan-env"), "update_plan",
                        environmentPlanJson()))));
        FakeTool logQuery = FakeTool.readOnlyReturning("LogQuery", "must not run");
        ToolRegistry tools = new ToolRegistry().register(logQuery);
        DiagnosisOrchestrator.Options options = new DiagnosisOrchestrator.Options(
                AgentBudget.unlimited(), codec,
                new StructuredDiagnosisPlanner(llm, Set.of("LogQuery")), null,
                com.anthropic.agentkit.application.diagnosis.PlanGuardMode.OBSERVE, "");
        RunRequest mismatch = RunRequest.builder()
                .workingDir(System.getProperty("user.dir"))
                .userMessage("查看生产环境最近两小时错误日志")
                .sessionId("env-mismatch")
                .operationalContext(new OperationalContext(
                        Instant.parse("2026-07-30T02:00:00Z"), ZoneId.of("UTC"),
                        EnvironmentContext.named("test"), "agent-web", List.of(), Map.of()))
                .build();
        List<String> lines = new ArrayList<>();

        OrchestrationResult result = new DiagnosisOrchestrator(llm, tools, options).run(
                mismatch, conversation("env-mismatch"), new CancellationToken(), lines::add);

        assertThat(logQuery.callCount()).isZero();
        assertThat(result.outcome()).isEqualTo(DiagnosisOutcome.ENVIRONMENT_MISMATCH);
        assertThat(lines.stream().map(this::typeOf)).contains("diagnosis_blocked");
    }

    private DiagnosisOrchestrator orchestrator(StubLlmClient llm, ToolRegistry tools) {
        return new DiagnosisOrchestrator(llm, tools, AgentBudget.unlimited(), codec);
    }

    private DiagnosisOrchestrator configuredOrchestrator(StubLlmClient llm, ToolRegistry tools,
                                                         com.anthropic.agentkit.application.diagnosis.DiagnosisPlanner planner,
                                                         FakeReporter reporter) {
        return new DiagnosisOrchestrator(llm, tools, new DiagnosisOrchestrator.Options(
                AgentBudget.unlimited(),
                codec,
                planner,
                reporter,
                com.anthropic.agentkit.application.diagnosis.PlanGuardMode.OBSERVE,
                ""));
    }

    private static RunRequest request(String snapshot) {
        return RunRequest.builder()
                .workingDir(System.getProperty("user.dir"))
                .userMessage("hi")
                .sessionId("s-1")
                .stateSnapshot(snapshot)
                .build();
    }

    private static Conversation conversation() {
        Conversation conversation = new Conversation(SessionId.of("s-1"));
        conversation.append(UserMessage.of("hi"));
        return conversation;
    }

    private static Conversation conversation(String sessionId) {
        Conversation conversation = new Conversation(SessionId.of(sessionId));
        conversation.append(UserMessage.of("查看生产环境最近两小时错误日志"));
        return conversation;
    }

    private static String environmentPlanJson() {
        return """
                {
                  "problemStatement": "production logs",
                  "hypotheses": [{"id":"H1","statement":"service error","confidence":0.3}],
                  "steps": [{"id":"S1","goal":"query logs","hypothesisId":"H1",
                    "allowedTools":["LogQuery"],"status":"PENDING"}],
                  "missingInputs": []
                }
                """;
    }

    private DiagnosisCase decodeSnapshot(List<String> lines) {
        String snapshot = lines.stream()
                .map(this::parse)
                .filter(node -> node.path("type").asText().equals("diagnosis_state"))
                .map(node -> node.path("payload").path("snapshot").asText())
                .findFirst()
                .orElseThrow();
        return codec.decode(snapshot).orElseThrow();
    }

    private static DiagnosisCase caseWithEvidence() {
        DiagnosisCase diagnosisCase = DiagnosisCase.open("s-1", "hi");
        diagnosisCase.adoptPlan(plan());
        diagnosisCase.recordToolEvidence(
                new ToolUseRequest(new ToolUseId("old-tu"), "LogQuery", "{}"),
                ToolResult.ok("old evidence"));
        return diagnosisCase;
    }

    private static DiagnosisPlan plan() {
        return new DiagnosisPlan(
                "hi",
                List.of(Hypothesis.open("H1", "入口服务报错", 0.4)),
                List.of(new DiagnosisStep("S1", "查日志", "H1", List.of("LogQuery"), StepStatus.RUNNING, "")));
    }

    private static class FakePlanner implements com.anthropic.agentkit.application.diagnosis.DiagnosisPlanner {

        @Override
        public DiagnosisPlan createPlan(DiagnosisCase diagnosisCase, AgentRunContext context) {
            return plan();
        }

        @Override
        public DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence,
                                        AgentRunContext context) {
            return plan();
        }
    }

    private static final class FixedPlanPlanner extends FakePlanner {

        private final DiagnosisPlan fixed;

        private FixedPlanPlanner(DiagnosisPlan fixed) {
            this.fixed = fixed;
        }

        @Override
        public DiagnosisPlan createPlan(DiagnosisCase diagnosisCase, AgentRunContext context) {
            return fixed;
        }
    }

    private static final class BlockingUpdatePlanner extends FakePlanner {

        @Override
        public DiagnosisPlan updatePlan(
                DiagnosisCase diagnosisCase, Evidence evidence, String conversationContext,
                OperationalContext operationalContext,
                DiagnosisExecutionCapabilities capabilities, AgentRunContext context) {
            return new DiagnosisPlan(
                    "backend unavailable", plan().hypotheses(), plan().steps(), List.of(),
                    DiagnosisScope.unknown(), List.of(new DiagnosisBlocker(
                    DiagnosisBlockerType.BACKEND_UNHEALTHY,
                    "DIAGNOSIS_BACKEND_UNHEALTHY", "log backend is unavailable",
                    "restore backend health", false)));
        }
    }

    private static final class UpdatingPlanner extends FakePlanner {

        private int updateCalls;

        @Override
        public DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence,
                                        AgentRunContext context) {
            updateCalls++;
            return new DiagnosisPlan(
                    "updated",
                    List.of(Hypothesis.open("H1", "log errors", 0.7)),
                    List.of(new DiagnosisStep("S1", "read logs", "H1",
                            List.of("LogQuery"), StepStatus.RUNNING, "")));
        }
    }

    private static final class MissingInputPlanner
            implements com.anthropic.agentkit.application.diagnosis.DiagnosisPlanner {

        @Override
        public DiagnosisPlan createPlan(DiagnosisCase diagnosisCase, AgentRunContext context) {
            return new DiagnosisPlan(
                    "need scope",
                    List.of(Hypothesis.open("H1", "missing scope", 0.1)),
                    List.of(new DiagnosisStep("S1", "ask trace", "H1",
                            List.of("LogQuery"), StepStatus.PENDING, "")),
                    List.of("traceId", "timeWindow"));
        }

        @Override
        public DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence,
                                        AgentRunContext context) {
            return createPlan(diagnosisCase, context);
        }
    }

    private static final class CapabilityPlanner extends FakePlanner {

        private DiagnosisExecutionCapabilities capabilities;
        private OperationalContext operationalContext;

        @Override
        public DiagnosisPlan createPlan(
                DiagnosisCase diagnosisCase, String conversationContext,
                com.anthropic.agentkit.domain.diagnosis.OperationalContext operationalContext,
                DiagnosisExecutionCapabilities capabilities, AgentRunContext context) {
            this.capabilities = capabilities;
            this.operationalContext = operationalContext;
            return plan().withGenerations(
                    capabilities.generation(), capabilities.resources().generation());
        }
    }

    private static final class FakeReporter
            implements com.anthropic.agentkit.application.diagnosis.DiagnosisReporter {

        private int calls;

        @Override
        public DiagnosisReport report(DiagnosisCase diagnosisCase, AgentRunContext context) {
            calls++;
            return new DiagnosisReport("summary", List.of(), List.of(), List.of(), 0.1, true);
        }
    }

    private String typeOf(String line) {
        return parse(line).path("type").asText();
    }

    private JsonNode parse(String line) {
        try {
            return mapper.readTree(line);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
