package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.application.diagnosis.DiagnosisPlanner;
import com.anthropic.agentkit.application.diagnosis.DiagnosisReporter;
import com.anthropic.agentkit.application.diagnosis.PlanGuardMode;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.diagnosis.DataSourceBinding;
import com.anthropic.agentkit.domain.diagnosis.DataSourceType;
import com.anthropic.agentkit.domain.diagnosis.DataSourceView;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisBlockerType;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisExecutionCapabilities;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisReport;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalogSnapshot;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisScope;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisStep;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentContext;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentRef;
import com.anthropic.agentkit.domain.diagnosis.Evidence;
import com.anthropic.agentkit.domain.diagnosis.Hypothesis;
import com.anthropic.agentkit.domain.diagnosis.OperationalContext;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.anthropic.agentkit.domain.diagnosis.RootCauseCandidate;
import com.anthropic.agentkit.domain.diagnosis.ServiceRef;
import com.anthropic.agentkit.domain.diagnosis.StepStatus;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisStateCodec;
import com.anthropic.agentkit.infrastructure.diagnosis.StructuredDiagnosisPlanner;
import com.anthropic.agentkit.infrastructure.tools.support.BackendErrorCode;
import com.anthropic.agentkit.infrastructure.tools.support.BackendFailure;
import com.anthropic.agentkit.infrastructure.tools.support.BackendQueryException;
import com.anthropic.agentkit.infrastructure.tools.support.BackendRetryPolicy;
import com.anthropic.agentkit.infrastructure.tools.support.LogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.LogQueryRequest;
import com.anthropic.agentkit.infrastructure.tools.support.LogQueryResult;
import com.anthropic.agentkit.infrastructure.tools.support.ResilientLogQueryClient;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executable acceptance matrix for the DIA-75 offline golden cases.
 *
 * @author alex
 * @since 2026-07-30
 */
class DiagnosisGoldenCasesTest {

    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DiagnosisStateCodec stateCodec = new DiagnosisStateCodec();

    @Test
    void greetingNoTools_isANonIncidentWithoutCapabilityBlocker() {
        DiagnosisPlan plan = structuredPlan(
                "你好", greetingPlanJson(), Set.of(), OperationalContext.unknown());

        assertThat(plan.steps()).isEmpty();
        assertThat(plan.hypotheses()).isEmpty();
        assertThat(plan.blockers()).isEmpty();
    }

    @Test
    void diagnosisNoTools_returnsCapabilityUnavailable() {
        DiagnosisPlan plan = structuredPlan(
                "查看最近两小时错误日志", incidentPlanJson(), Set.of(), fixedContext("test"));

        assertThat(plan.blockers()).singleElement().satisfies(blocker -> {
            assertThat(blocker.type()).isEqualTo(DiagnosisBlockerType.CAPABILITY_UNAVAILABLE);
            assertThat(blocker.code()).isEqualTo("LOG_QUERY_NOT_CONFIGURED");
        });
    }

    @Test
    void localLogDefaults_resolveServiceAndExecuteLogQuery() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolUse("local-tu", "LogQuery", "{}"))
                .enqueue(AiMessage.text("diagnosis complete"));
        FakeTool logQuery = FakeTool.readOnlyReturning("LogQuery", "NullPointerException");
        CapturingPlanner planner = new CapturingPlanner(runningPlan("local logs"));
        List<String> events = new ArrayList<>();

        OrchestrationResult result = orchestrator(
                llm, new ToolRegistry().register(logQuery), planner, null, singleServiceCatalog())
                .run(request("local", "查看最近两小时错误日志", fixedContext("test"), ""),
                        conversation("local", "查看最近两小时错误日志"),
                        new CancellationToken(), events::add);

        assertThat(planner.operationalContext.defaultService()).isEqualTo("agent-web");
        assertThat(planner.operationalContext.dataSources()).extracting("id")
                .containsExactly("local-agent-web-logs");
        assertThat(logQuery.callCount()).isEqualTo(1);
        assertThat(stateCodec.decode(result.stateSnapshot()).orElseThrow().ledger().all())
                .singleElement().satisfies(evidence ->
                        assertThat(evidence.toolUseId()).isEqualTo("local-tu"));
    }

    @Test
    void environmentMismatch_neverExecutesEvidenceTool() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolUse("env-plan", "update_plan", incidentPlanJson()));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(
                llm, Set.of("LogQuery"));
        FakeTool logQuery = FakeTool.readOnlyReturning("LogQuery", "must not run");

        OrchestrationResult result = orchestrator(
                llm, new ToolRegistry().register(logQuery), planner, null,
                DiagnosisResourceCatalogSnapshot.empty()).run(
                        request("env", "查看生产环境日志", fixedContext("test"), ""),
                        conversation("env", "查看生产环境日志"),
                        new CancellationToken(), ignored -> { });

        assertThat(result.blockers()).singleElement().satisfies(blocker ->
                assertThat(blocker.type()).isEqualTo(DiagnosisBlockerType.ENVIRONMENT_MISMATCH));
        assertThat(result.outcome()).isEqualTo(DiagnosisOutcome.ENVIRONMENT_MISMATCH);
        assertThat(llm.capturedRequests()).hasSize(1);
        assertThat(logQuery.callCount()).isZero();
    }

    @Test
    void relativeTime_usesHostClockAndZoneToCreateAbsoluteWindow() {
        DiagnosisPlan plan = structuredPlan(
                "查看最近两小时错误日志", incidentPlanJson(), Set.of("LogQuery"),
                fixedContext("test"));

        assertThat(plan.scope().timeWindow().startInclusive())
                .isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
        assertThat(plan.scope().timeWindow().endExclusive()).isEqualTo(NOW);
        assertThat(plan.missingInputs()).noneMatch(input -> input.contains("时间"));
    }

    @Test
    void missingService_asksOnlyForAServiceSelection() {
        OperationalContext context = fixedContext("test").withResources(twoServiceCatalog());
        DiagnosisPlan plan = structuredPlan(
                "查看最近两小时错误日志", incidentPlanJson(), Set.of("LogQuery"), context);

        assertThat(plan.missingInputs()).singleElement()
                .asString().contains("服务", "agent-web", "order-service");
        assertThat(plan.blockers()).singleElement().satisfies(blocker ->
                assertThat(blocker.type()).isEqualTo(DiagnosisBlockerType.USER_INPUT_REQUIRED));
        assertThat(plan.scope().timeWindow().isKnown()).isTrue();
    }

    @Test
    void backend401_isNotRetriedAndExposesOnlySafeFailure() {
        AtomicInteger calls = new AtomicInteger();
        LogQueryClient backend = request -> {
            calls.incrementAndGet();
            throw backendFailure(BackendErrorCode.AUTHENTICATION_FAILED, false);
        };
        ResilientLogQueryClient client = new ResilientLogQueryClient(
                backend, new BackendRetryPolicy(1, Duration.ofSeconds(1)));

        assertThatThrownBy(() -> client.queryResult(logRequest()))
                .isInstanceOfSatisfying(BackendQueryException.class, failure -> {
                    assertThat(failure.retryCount()).isZero();
                    assertThat(failure.getMessage()).isEqualTo("diagnosis backend request failed");
                });
        assertThat(calls).hasValue(1);
    }

    @Test
    void backend429_retriesOnceAndPublishesRetryMetadata() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LogQueryClient backend = request -> {
            if (calls.getAndIncrement() == 0) {
                throw backendFailure(BackendErrorCode.RATE_LIMITED, true);
            }
            return "recovered";
        };
        ResilientLogQueryClient client = new ResilientLogQueryClient(
                backend, new BackendRetryPolicy(1, Duration.ofSeconds(1)));

        LogQueryResult result = client.queryResult(logRequest());

        assertThat(calls).hasValue(2);
        assertThat(result.content()).isEqualTo("recovered");
        assertThat(result.retryCount()).isEqualTo(1);
    }

    @Test
    void evidenceReport_referencesTheRealToolEvidence() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolUse("npe-tu", "LogQuery", "{}"))
                .enqueue(AiMessage.text("NPE found"));
        Tool logQuery = new EvidenceTool();
        List<String> events = new ArrayList<>();

        OrchestrationResult result = orchestrator(
                llm, new ToolRegistry().register(logQuery),
                new CapturingPlanner(runningPlan("NPE diagnosis")), new EvidenceReporter(),
                singleServiceCatalog()).run(
                        request("evidence", "定位 NPE", fixedContext("test"), ""),
                        conversation("evidence", "定位 NPE"), new CancellationToken(), events::add);

        Evidence evidence = stateCodec.decode(result.stateSnapshot()).orElseThrow()
                .ledger().all().getFirst();
        JsonNode report = event(events, "diagnosis_report").path("payload").path("report");
        assertThat(evidence.toolUseId()).isEqualTo("npe-tu");
        assertThat(evidence.metadata()).containsEntry("dataSourceId", "local-agent-web-logs");
        assertThat(report.at("/rootCauseCandidates/0/evidenceIds/0").asText())
                .isEqualTo(evidence.id());
        assertThat(report.path("keyEvidenceIds")).extracting(JsonNode::asText)
                .containsExactly(evidence.id());
    }

    @Test
    void followUpAfterDone_createsANewPlanAndKeepsPriorEvidence() {
        DiagnosisCase prior = completedCase();
        CapturingPlanner planner = new CapturingPlanner(runningPlan("follow-up plan"));
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("follow-up complete"));

        OrchestrationResult result = orchestrator(
                llm, new ToolRegistry(), planner, null,
                DiagnosisResourceCatalogSnapshot.empty()).run(
                        request("follow-up", "继续看另一个异常", fixedContext("test"),
                                stateCodec.encode(prior)),
                        conversation("follow-up", "继续看另一个异常"),
                        new CancellationToken(), ignored -> { });

        DiagnosisCase restored = stateCodec.decode(result.stateSnapshot()).orElseThrow();
        assertThat(restored.plan().problemStatement()).isEqualTo("follow-up plan");
        assertThat(restored.ledger().all()).singleElement().satisfies(evidence ->
                assertThat(evidence.toolUseId()).isEqualTo("prior-tu"));
    }

    @Test
    void unavailableConfiguredBackend_returnsBackendUnhealthy() {
        OperationalContext context = new OperationalContext(
                NOW, ZoneId.of("UTC"), EnvironmentContext.named("test"), "agent-web",
                List.of(new DataSourceView("logs", DataSourceType.LOG,
                        ReadinessStatus.UNAVAILABLE, Set.of("query"))), Map.of());
        DiagnosisPlan plan = structuredPlan(
                "查看错误日志", incidentPlanJson(), Set.of("LogQuery"), context);

        assertThat(plan.blockers()).singleElement().satisfies(blocker -> {
            assertThat(blocker.type()).isEqualTo(DiagnosisBlockerType.BACKEND_UNHEALTHY);
            assertThat(blocker.code()).isEqualTo("DIAGNOSIS_BACKEND_UNHEALTHY");
        });
    }

    private DiagnosisPlan structuredPlan(String question, String json, Set<String> tools,
                                         OperationalContext context) {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolUse("golden-plan", "update_plan", json));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(llm, tools);
        return planner.createPlan(
                DiagnosisCase.open("golden", question), "Current user input: " + question,
                context, AgentRunContext.at(Path.of(".")));
    }

    private DiagnosisOrchestrator orchestrator(
            StubLlmClient llm, ToolRegistry tools, DiagnosisPlanner planner,
            DiagnosisReporter reporter, DiagnosisResourceCatalogSnapshot resources) {
        DiagnosisOrchestrator.Options options = new DiagnosisOrchestrator.Options(
                AgentBudget.unlimited(), stateCodec, planner, reporter,
                PlanGuardMode.OBSERVE, "", "", () -> resources);
        return new DiagnosisOrchestrator(llm, tools, options);
    }

    private static RunRequest request(String sessionId, String message,
                                      OperationalContext context, String snapshot) {
        return RunRequest.builder()
                .workingDir(System.getProperty("user.dir"))
                .userMessage(message)
                .sessionId(sessionId)
                .operationalContext(context)
                .stateSnapshot(snapshot)
                .build();
    }

    private static Conversation conversation(String sessionId, String message) {
        Conversation conversation = new Conversation(SessionId.of(sessionId));
        conversation.append(UserMessage.of(message));
        return conversation;
    }

    private static OperationalContext fixedContext(String environment) {
        return new OperationalContext(
                NOW, ZoneId.of("UTC"), EnvironmentContext.named(environment),
                "", List.of(), Map.of());
    }

    private static DiagnosisResourceCatalogSnapshot singleServiceCatalog() {
        ServiceRef service = new ServiceRef("agent-web", Set.of("web"));
        return catalog(List.of(service), List.of(binding(service, "local-agent-web-logs")));
    }

    private static DiagnosisResourceCatalogSnapshot twoServiceCatalog() {
        ServiceRef web = new ServiceRef("agent-web", Set.of("web"));
        ServiceRef orders = new ServiceRef("order-service", Set.of("orders"));
        return catalog(List.of(web, orders), List.of(
                binding(web, "agent-web-logs"), binding(orders, "order-service-logs")));
    }

    private static DiagnosisResourceCatalogSnapshot catalog(
            List<ServiceRef> services, List<DataSourceBinding> bindings) {
        return new DiagnosisResourceCatalogSnapshot(7, services, bindings);
    }

    private static DataSourceBinding binding(ServiceRef service, String dataSourceId) {
        return new DataSourceBinding(
                EnvironmentRef.named("test"), service, dataSourceId, DataSourceType.LOG,
                "LogQuery", ReadinessStatus.READY, true, Set.of("query"), Map.of());
    }

    private static DiagnosisPlan runningPlan(String problem) {
        return new DiagnosisPlan(
                problem, List.of(Hypothesis.open("H1", "application failure", 0.5)),
                List.of(new DiagnosisStep("S1", "query logs", "H1",
                        List.of("LogQuery"), StepStatus.RUNNING, "")),
                List.of(), DiagnosisScope.unknown(), List.of());
    }

    private static AiMessage toolUse(String id, String name, String arguments) {
        return new AiMessage("", List.of(
                new ToolUseRequest(new ToolUseId(id), name, arguments)));
    }

    private DiagnosisCase completedCase() {
        DiagnosisCase diagnosisCase = DiagnosisCase.open("follow-up", "first incident");
        diagnosisCase.adoptPlan(runningPlan("first plan"));
        diagnosisCase.recordToolEvidence(
                new ToolUseRequest(new ToolUseId("prior-tu"), "LogQuery", "{}"),
                ToolResult.ok("prior evidence"));
        diagnosisCase.markDone();
        return diagnosisCase;
    }

    private static LogQueryRequest logRequest() {
        return new LogQueryRequest(
                "trace-1", "", "agent-web", "2026-07-30T00:00:00Z",
                "2026-07-30T02:00:00Z", "ERROR", 20);
    }

    private static BackendQueryException backendFailure(
            BackendErrorCode code, boolean retryable) {
        return new BackendQueryException(new BackendFailure(
                code, retryable, "diagnosis backend request failed"));
    }

    private static JsonNode event(List<String> events, String type) {
        return events.stream().map(DiagnosisGoldenCasesTest::parse)
                .filter(node -> type.equals(node.path("type").asText()))
                .findFirst().orElseThrow();
    }

    private static JsonNode parse(String event) {
        try {
            return JSON.readTree(event);
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid stream event", failure);
        }
    }

    private static String greetingPlanJson() {
        return """
                {"problemStatement":"greeting","hypotheses":[],"steps":[],"missingInputs":[]}
                """;
    }

    private static String incidentPlanJson() {
        return """
                {
                  "problemStatement":"application errors",
                  "hypotheses":[{"id":"H1","statement":"application failure","confidence":0.5}],
                  "steps":[{"id":"S1","goal":"query logs","hypothesisId":"H1",
                    "allowedTools":["LogQuery"],"status":"PENDING"}],
                  "missingInputs":[]
                }
                """;
    }

    private static final class CapturingPlanner implements DiagnosisPlanner {

        private final DiagnosisPlan plan;
        private OperationalContext operationalContext = OperationalContext.unknown();

        private CapturingPlanner(DiagnosisPlan plan) {
            this.plan = plan;
        }

        @Override
        public DiagnosisPlan createPlan(DiagnosisCase diagnosisCase, AgentRunContext context) {
            return plan;
        }

        @Override
        public DiagnosisPlan createPlan(
                DiagnosisCase diagnosisCase, String conversationContext,
                OperationalContext operational, DiagnosisExecutionCapabilities capabilities,
                AgentRunContext context) {
            operationalContext = operational;
            return plan.withGenerations(
                    capabilities.generation(), capabilities.resources().generation());
        }

        @Override
        public DiagnosisPlan updatePlan(
                DiagnosisCase diagnosisCase, Evidence evidence, AgentRunContext context) {
            return plan;
        }
    }

    private static final class EvidenceReporter implements DiagnosisReporter {

        @Override
        public DiagnosisReport report(DiagnosisCase diagnosisCase, AgentRunContext context) {
            Evidence evidence = diagnosisCase.ledger().all().getFirst();
            RootCauseCandidate candidate = new RootCauseCandidate(
                    "H1", "OrderService dereferenced a null value",
                    List.of(evidence.id()), 0.9, true);
            return new DiagnosisReport(
                    "NPE confirmed by the bounded log result", List.of(candidate),
                    List.of(evidence.id()), List.of("inspect OrderService.java:42"),
                    0.9, false);
        }
    }

    private static final class EvidenceTool implements Tool {

        @Override
        public String name() {
            return "LogQuery";
        }

        @Override
        public String description() {
            return "bounded golden-case log query";
        }

        @Override
        public String inputSchema() {
            return "{}";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public ToolResult execute(ToolArguments args, ExecutionContext context) {
            return ToolResult.of(
                    ToolResultStatus.SUCCESS, "NullPointerException at OrderService.java:42",
                    Map.of("dataSourceId", "local-agent-web-logs", "environment", "test",
                            "service", "agent-web", "queryStart", "2026-07-30T00:00:00Z",
                            "queryEnd", "2026-07-30T02:00:00Z", "matched", "1",
                            "returned", "1", "truncated", "false", "retryCount", "0"));
        }
    }
}
