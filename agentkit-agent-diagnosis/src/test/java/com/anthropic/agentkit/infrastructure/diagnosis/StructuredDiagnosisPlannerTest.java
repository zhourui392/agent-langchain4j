package com.anthropic.agentkit.infrastructure.diagnosis;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisExecutionCapabilities;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalogSnapshot;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisToolMetadata;
import com.anthropic.agentkit.domain.diagnosis.DataSourceType;
import com.anthropic.agentkit.domain.diagnosis.DataSourceView;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentContext;
import com.anthropic.agentkit.domain.diagnosis.OperationalContext;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.anthropic.agentkit.domain.diagnosis.StepStatus;
import com.anthropic.agentkit.domain.diagnosis.Evidence;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredDiagnosisPlannerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void createsPlanFromUpdatePlanToolUse() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-1"), "update_plan", planJson()))))
                .enqueue(AiMessage.text("planned"));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(llm);

        DiagnosisPlan plan = planner.createPlan(
                DiagnosisCase.open("case-1", "订单失败"), AgentRunContext.at(Path.of(".")));

        assertThat(plan.problemStatement()).isEqualTo("订单失败");
        assertThat(plan.hypotheses()).singleElement()
                .satisfies(hypothesis -> assertThat(hypothesis.id()).isEqualTo("H1"));
        assertThat(plan.steps()).singleElement().satisfies(step -> {
            assertThat(step.allowedTools()).containsExactly("LogQuery");
            assertThat(step.status()).isEqualTo(StepStatus.PENDING);
        });
        assertThat(plan.missingInputs()).containsExactly("timeWindow");
        assertThat(llm.capturedRequests().get(0).tools())
                .extracting(com.anthropic.agentkit.domain.port.ToolSpec::name)
                .contains("update_plan");
    }

    @Test
    void retriesWhenModelReturnsEmptyAllowedTools() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-empty-tools"), "update_plan",
                        planJsonWithEmptyAllowedTools()))))
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-without-steps"), "update_plan",
                        planJsonWithoutSteps()))));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(llm);

        DiagnosisPlan plan = planner.createPlan(
                DiagnosisCase.open("case-empty-tools", "订单失败"),
                AgentRunContext.at(Path.of(".")));

        assertThat(plan.steps()).isEmpty();
        assertThat(llm.capturedRequests()).hasSize(2);
    }

    @Test
    void dropsToolsThatAreNotRegisteredForDiagnosis() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-unavailable-tool"), "update_plan", planJson()))))
                .enqueue(AiMessage.text("planned"));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(llm, Set.of());

        DiagnosisPlan plan = planner.createPlan(
                DiagnosisCase.open("case-unavailable-tool", "订单失败"),
                AgentRunContext.at(Path.of(".")));

        assertThat(plan.steps()).isEmpty();
        assertThat(plan.missingInputs()).isEmpty();
        assertThat(plan.blockers()).singleElement().satisfies(blocker -> {
            assertThat(blocker.type()).isEqualTo(
                    com.anthropic.agentkit.domain.diagnosis.DiagnosisBlockerType.CAPABILITY_UNAVAILABLE);
            assertThat(blocker.code()).isEqualTo("LOG_QUERY_NOT_CONFIGURED");
        });
        assertThat(llm.capturedRequests().get(0).systemPrompt())
                .contains("No diagnosis tools are available", "greeting", "missingInputs");
    }

    @Test
    void greetingDoesNotRequireCapabilities() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-greeting"), "update_plan", greetingPlanJson()))));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(llm, Set.of());

        DiagnosisPlan plan = planner.createPlan(
                DiagnosisCase.open("case-greeting", "你好"), AgentRunContext.at(Path.of(".")));

        assertThat(plan.steps()).isEmpty();
        assertThat(plan.blockers()).isEmpty();
    }

    @Test
    void unavailableConfiguredBackend_shouldNotBeReportedAsMissingConfiguration() {
        StubLlmClient llm = new StubLlmClient().enqueue(new AiMessage("", List.of(
                new ToolUseRequest(new ToolUseId("plan-unhealthy"),
                        "update_plan", planJson()))));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(llm, Set.of());
        OperationalContext operational = new OperationalContext(
                Instant.parse("2026-07-30T02:00:00Z"), ZoneId.of("UTC"),
                EnvironmentContext.named("prod"), "agent-web",
                List.of(new DataSourceView("prod-logs", DataSourceType.LOG,
                        ReadinessStatus.UNAVAILABLE, Set.of("query"))), Map.of());

        DiagnosisPlan plan = planner.createPlan(
                DiagnosisCase.open("case-unhealthy", "查看错误日志"),
                "Current user input: 查看错误日志", operational,
                AgentRunContext.at(Path.of(".")));

        assertThat(plan.blockers()).singleElement().satisfies(blocker -> {
            assertThat(blocker.type()).isEqualTo(
                    com.anthropic.agentkit.domain.diagnosis.DiagnosisBlockerType.BACKEND_UNHEALTHY);
            assertThat(blocker.code()).isEqualTo("DIAGNOSIS_BACKEND_UNHEALTHY");
        });
    }

    @Test
    void includesCurrentConversationContextInPlanningRequest() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-current-context"), "update_plan", planJson()))));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(llm);

        planner.createPlan(
                DiagnosisCase.open("case-current-context", "订单失败"),
                "Previous user input: 生产环境。\nCurrent user input: traceId=abc",
                AgentRunContext.at(Path.of(".")));

        UserMessage request = (UserMessage) llm.capturedRequests().get(0).messages().get(0);
        assertThat(request.text())
                .contains("订单失败", "生产环境", "traceId=abc");
    }

    @Test
    void includesOperationalContextInPlanningRequest() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-operational-context"), "update_plan", planJson()))))
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-resolved-scope"), "update_plan", planJson()))));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(llm, Set.of("LogQuery"));
        OperationalContext operational = new OperationalContext(
                Instant.parse("2026-07-30T02:00:00Z"), ZoneId.of("UTC"),
                new EnvironmentContext("test", "local", "cn-east"), "agent-web",
                List.of(new DataSourceView("local-agent-web-logs", DataSourceType.LOG,
                        ReadinessStatus.READY, Set.of("query"))),
                Map.of("namespace", "agent-web-test"));

        planner.createPlan(
                DiagnosisCase.open("case-operational-context", "查看最近两小时错误日志"),
                "Current user input: 查看最近两小时错误日志", operational,
                AgentRunContext.at(Path.of(".")));

        UserMessage request = (UserMessage) llm.capturedRequests().get(0).messages().get(0);
        assertThat(request.text())
                .contains("2026-07-30T02:00:00Z", "UTC", "test", "local", "cn-east",
                        "agent-web", "local-agent-web-logs", "LOG", "READY", "namespace");
        assertThat(request.text()).doesNotContain("must-not-enter-context");
        DiagnosisPlan plan = planner.createPlan(
                DiagnosisCase.open("case-resolved-scope", "查看最近两小时错误日志"),
                "Current user input: 查看最近两小时错误日志", operational,
                AgentRunContext.at(Path.of(".")));
        assertThat(plan.scope().environment().name()).isEqualTo("test");
        assertThat(plan.scope().services()).containsExactly("agent-web");
        assertThat(plan.scope().timeWindow().startInclusive())
                .isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
        assertThat(plan.scope().timeWindow().endExclusive())
                .isEqualTo(Instant.parse("2026-07-30T02:00:00Z"));
        assertThat(plan.missingInputs()).noneMatch(value -> value.contains("timeWindow"));
    }

    @Test
    void hostResolvedRelativeWindowOverridesModelExpandedScope() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-expanded-window"), "update_plan",
                        planJsonWithExpandedWindow()))));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(
                llm, Set.of("LogQuery"));

        DiagnosisPlan plan = planner.createPlan(
                DiagnosisCase.open("case-expanded-window", "查看最近两小时错误日志"),
                "Current user input: 查看最近两小时错误日志", operational("test"),
                AgentRunContext.at(Path.of(".")));

        assertThat(plan.scope().timeWindow().startInclusive())
                .isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
        assertThat(plan.scope().timeWindow().endExclusive())
                .isEqualTo(Instant.parse("2026-07-30T02:00:00Z"));
    }

    @Test
    void modelWindowCannotExceedHostPolicyOrHostNow() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-future-window"), "update_plan",
                        planJsonWithFutureWindow()))));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(
                llm, Set.of("LogQuery"));

        DiagnosisPlan plan = planner.createPlan(
                DiagnosisCase.open("case-future-window", "查看错误日志"),
                "Current user input: 查看错误日志", operational("test"),
                AgentRunContext.at(Path.of(".")));

        assertThat(plan.scope().timeWindow().isKnown()).isFalse();
    }

    @Test
    void updatePlanRetainsConversationAndOperationalContext() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("replan-context"), "update_plan", planJson()))));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(
                llm, Set.of("LogQuery"));
        DiagnosisCase diagnosisCase = DiagnosisCase.open(
                "case-replan-context", "查看最近两小时错误日志");
        diagnosisCase.adoptPlan(new DiagnosisPlan(
                "initial", List.of(), List.of()));
        Evidence evidence = diagnosisCase.recordToolEvidence(
                new ToolUseRequest(new ToolUseId("log-query-1"), "LogQuery", "{}"),
                ToolResult.error("invalid query"));
        OperationalContext operational = operational("test");
        DiagnosisExecutionCapabilities capabilities = new DiagnosisExecutionCapabilities(
                11L, Set.of("LogQuery"), DiagnosisResourceCatalogSnapshot.empty());

        DiagnosisPlan updated = planner.updatePlan(
                diagnosisCase, evidence,
                "Current user input: 查看最近两小时错误日志", operational,
                capabilities, AgentRunContext.at(Path.of(".")));

        UserMessage request = (UserMessage) llm.capturedRequests().getFirst().messages().getFirst();
        assertThat(request.text()).contains(
                "Current user input: 查看最近两小时错误日志",
                "2026-07-30T02:00:00Z", "test", "agent-web", "logs",
                "Current diagnosis plan", "Latest evidence",
                "E1", "log-query-1", "invalid query", "untrusted diagnostic data");
        assertThat(updated.scope().environment().name()).isEqualTo("test");
        assertThat(updated.scope().services()).containsExactly("agent-web");
        assertThat(updated.scope().timeWindow().startInclusive())
                .isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
        assertThat(updated.scope().timeWindow().endExclusive())
                .isEqualTo(Instant.parse("2026-07-30T02:00:00Z"));
        assertThat(updated.blockers()).noneMatch(
                blocker -> "NO_LOG_DATASOURCE".equals(blocker.code()));
    }

    @Test
    void exhaustedTransientBackendFailureDeterministicallyBlocksTheUpdatedPlan() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("replan-backend-failure"), "update_plan", planJson()))));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(
                llm, Set.of("LogQuery"));
        DiagnosisCase diagnosisCase = DiagnosisCase.open(
                "case-backend-failure", "查看最近两小时错误日志");
        diagnosisCase.adoptPlan(new DiagnosisPlan(
                "initial", List.of(), List.of()));
        Evidence evidence = diagnosisCase.recordToolEvidence(
                new ToolUseRequest(new ToolUseId("log-query-rate-limited"), "LogQuery", "{}"),
                ToolResult.of(ToolResultStatus.ERROR, "backend rate limit reached", Map.of(
                        DiagnosisToolMetadata.BACKEND_STATUS, "FAILED",
                        DiagnosisToolMetadata.ERROR_CODE, "RATE_LIMITED",
                        DiagnosisToolMetadata.RETRY_COUNT, "1")));

        DiagnosisPlan updated = planner.updatePlan(
                diagnosisCase, evidence,
                "Current user input: 查看最近两小时错误日志", operational("test"),
                new DiagnosisExecutionCapabilities(12L, Set.of("LogQuery")),
                AgentRunContext.at(Path.of(".")));

        assertThat(updated.blockers()).singleElement().satisfies(blocker -> {
            assertThat(blocker.type()).isEqualTo(
                    com.anthropic.agentkit.domain.diagnosis.DiagnosisBlockerType.BACKEND_UNHEALTHY);
            assertThat(blocker.code()).isEqualTo("BACKEND_RATE_LIMITED");
            assertThat(blocker.userActionable()).isFalse();
        });
    }

    @Test
    void environmentMismatch_shouldBeDeterministicInBothDirections() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-prod-to-test"), "update_plan", planJson()))))
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-test-to-prod"), "update_plan", planJson()))));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(
                llm, Set.of("LogQuery"));

        DiagnosisPlan prod = planner.createPlan(
                DiagnosisCase.open("prod-case", "查看测试环境日志"),
                "Current user input: 查看测试环境日志", operational("prod"),
                AgentRunContext.at(Path.of(".")));
        DiagnosisPlan test = planner.createPlan(
                DiagnosisCase.open("test-case", "查看生产环境日志"),
                "Current user input: 查看生产环境日志", operational("test"),
                AgentRunContext.at(Path.of(".")));

        assertThat(prod.blockers()).singleElement().satisfies(blocker ->
                assertThat(blocker.type()).isEqualTo(
                        com.anthropic.agentkit.domain.diagnosis.DiagnosisBlockerType.ENVIRONMENT_MISMATCH));
        assertThat(test.blockers()).singleElement().satisfies(blocker ->
                assertThat(blocker.type()).isEqualTo(
                        com.anthropic.agentkit.domain.diagnosis.DiagnosisBlockerType.ENVIRONMENT_MISMATCH));
        assertThat(prod.scope().environment().name()).isEqualTo("prod");
        assertThat(test.scope().environment().name()).isEqualTo("test");
    }

    @Test
    void updatePlanToolSchema_shouldDescribeTheObjectArraysRequiredByThePlanDto()
            throws Exception {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-schema"), "update_plan", planJson()))))
                .enqueue(AiMessage.text("planned"));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(llm);

        planner.createPlan(
                DiagnosisCase.open("case-schema", "订单失败"), AgentRunContext.at(Path.of(".")));

        JsonNode schema = JSON.readTree(llm.capturedRequests().get(0).tools().get(0).inputSchema());
        assertObjectArray(schema, "hypotheses", List.of("id", "statement", "confidence"));
        assertObjectArray(schema, "steps",
                List.of("id", "goal", "hypothesisId", "allowedTools"));
        assertThat(schema.at("/properties/steps/items/properties/allowedTools/minItems").asInt())
                .isEqualTo(1);
        assertThat(schema.at("/properties/missingInputs/items/type").asText())
                .isEqualTo("string");
        assertThat(schema.at("/properties/scope/type").asText()).isEqualTo("object");
        assertThat(schema.at("/properties/scope/properties/timeWindow/type").asText())
                .isEqualTo("object");
    }

    private static void assertObjectArray(JsonNode schema, String field,
                                          List<String> requiredFields) {
        JsonNode items = schema.path("properties").path(field).path("items");
        assertThat(items.path("type").asText()).isEqualTo("object");
        assertThat(items.path("required")).extracting(JsonNode::asText)
                .containsExactlyElementsOf(requiredFields);
        assertThat(items.path("properties").fieldNames()).toIterable()
                .containsAll(requiredFields);
    }

    private static OperationalContext operational(String environment) {
        return new OperationalContext(
                Instant.parse("2026-07-30T02:00:00Z"), ZoneId.of("UTC"),
                EnvironmentContext.named(environment), "agent-web",
                List.of(new DataSourceView("logs", DataSourceType.LOG,
                        ReadinessStatus.READY, Set.of("query"))), Map.of());
    }

    private static String planJson() {
        return """
                {
                  "problemStatement": "订单失败",
                  "hypotheses": [
                    {"id": "H1", "statement": "入口服务报错", "confidence": 0.4}
                  ],
                  "steps": [
                    {
                      "id": "S1",
                      "goal": "查日志",
                      "hypothesisId": "H1",
                      "allowedTools": ["LogQuery"],
                      "status": "PENDING"
                    }
                  ],
                  "missingInputs": ["timeWindow"]
                }
                """;
    }

    private static String planJsonWithEmptyAllowedTools() {
        return """
                {
                  "problemStatement": "订单失败",
                  "hypotheses": [
                    {"id": "H1", "statement": "入口服务报错", "confidence": 0.4}
                  ],
                  "steps": [
                    {
                      "id": "S1",
                      "goal": "查日志",
                      "hypothesisId": "H1",
                      "allowedTools": [],
                      "status": "PENDING"
                    }
                  ]
                }
                """;
    }

    private static String planJsonWithoutSteps() {
        return """
                {
                  "problemStatement": "订单失败",
                  "hypotheses": [
                    {"id": "H1", "statement": "入口服务报错", "confidence": 0.4}
                  ],
                  "steps": []
                }
                """;
    }

    private static String greetingPlanJson() {
        return """
                {
                  "problemStatement": "用户问候",
                  "hypotheses": [],
                  "steps": [],
                  "missingInputs": []
                }
                """;
    }

    private static String planJsonWithExpandedWindow() {
        return """
                {
                  "problemStatement": "application errors",
                  "hypotheses": [
                    {"id": "H1", "statement": "application failure", "confidence": 0.5}
                  ],
                  "steps": [
                    {"id": "S1", "goal": "query logs", "hypothesisId": "H1",
                     "allowedTools": ["LogQuery"], "status": "PENDING"}
                  ],
                  "missingInputs": [],
                  "scope": {
                    "environment": "test",
                    "services": ["agent-web"],
                    "timeWindow": {
                      "startInclusive": "2026-07-29T02:00:00Z",
                      "endExclusive": "2026-07-30T02:00:00Z"
                    }
                  }
                }
                """;
    }

    private static String planJsonWithFutureWindow() {
        return """
                {
                  "problemStatement": "application errors",
                  "hypotheses": [
                    {"id": "H1", "statement": "application failure", "confidence": 0.5}
                  ],
                  "steps": [
                    {"id": "S1", "goal": "query logs", "hypothesisId": "H1",
                     "allowedTools": ["LogQuery"], "status": "PENDING"}
                  ],
                  "missingInputs": [],
                  "scope": {
                    "environment": "test",
                    "services": ["agent-web"],
                    "timeWindow": {
                      "startInclusive": "2026-07-30T02:00:00Z",
                      "endExclusive": "2026-07-30T03:00:00Z"
                    }
                  }
                }
                """;
    }
}
