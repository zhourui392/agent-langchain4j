package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.domain.agent.AgentBudget;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.diagnosis.DiagnosisCase;
import com.anthropic.cclc.domain.diagnosis.DiagnosisPlan;
import com.anthropic.cclc.domain.diagnosis.DiagnosisReport;
import com.anthropic.cclc.domain.diagnosis.DiagnosisStep;
import com.anthropic.cclc.domain.diagnosis.Evidence;
import com.anthropic.cclc.domain.diagnosis.Hypothesis;
import com.anthropic.cclc.domain.diagnosis.StepStatus;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnosisStateCodec;
import com.anthropic.cclc.testsupport.FakeTool;
import com.anthropic.cclc.testsupport.StubLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
        DiagnosisOrchestrator orchestrator = new DiagnosisOrchestrator(
                llm, new ToolRegistry(), AgentBudget.unlimited(), codec, new MissingInputPlanner());
        List<String> lines = new ArrayList<>();

        orchestrator.run(request(""), conversation(), new CancellationToken(), lines::add);

        assertThat(lines.stream().map(this::typeOf).toList())
                .contains("diagnosis_plan", "diagnosis_need_info", "diagnosis_state", "result");
        assertThat(llm.capturedRequests()).isEmpty();
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
    void emitsStructuredReportBeforeStateAndResult() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        DiagnosisOrchestrator orchestrator = configuredOrchestrator(
                llm, new ToolRegistry(), new FakePlanner(), new FakeReporter(), StepGuard.OBSERVE);
        List<String> lines = new ArrayList<>();

        orchestrator.run(request(""), conversation(), new CancellationToken(), lines::add);

        List<String> types = lines.stream().map(this::typeOf).toList();
        assertThat(types.indexOf("diagnosis_report")).isLessThan(types.indexOf("diagnosis_state"));
        assertThat(types.indexOf("diagnosis_report")).isLessThan(types.indexOf("result"));
    }

    @Test
    void denyPlanGuardBlocksOffPlanTool() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(
                        new ToolUseRequest(new ToolUseId("tu-1"), "RedisRead", "{}"))))
                .enqueue(AiMessage.text("done"));
        FakeTool redis = FakeTool.readOnlyReturning("RedisRead", "cache ok");
        ToolRegistry tools = new ToolRegistry().register(redis);
        DiagnosisOrchestrator orchestrator = configuredOrchestrator(
                llm, tools, new FakePlanner(), null, StepGuard.DENY);
        List<String> lines = new ArrayList<>();

        orchestrator.run(request(""), conversation(), new CancellationToken(), lines::add);

        assertThat(redis.callCount()).isZero();
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("permission denied: RedisRead"));
    }

    private DiagnosisOrchestrator orchestrator(StubLlmClient llm, ToolRegistry tools) {
        return new DiagnosisOrchestrator(llm, tools, AgentBudget.unlimited(), codec);
    }

    private DiagnosisOrchestrator configuredOrchestrator(StubLlmClient llm, ToolRegistry tools,
                                                         FakePlanner planner, FakeReporter reporter,
                                                         StepGuard guard) {
        return new DiagnosisOrchestrator(llm, tools, new DiagnosisOrchestrator.Options(
                AgentBudget.unlimited(),
                codec,
                planner,
                reporter,
                guard.mode(),
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

    private static class FakePlanner implements com.anthropic.cclc.application.diagnosis.DiagnosisPlanner {

        @Override
        public DiagnosisPlan createPlan(DiagnosisCase diagnosisCase) {
            return plan();
        }

        @Override
        public DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence) {
            return plan();
        }
    }

    private static final class UpdatingPlanner extends FakePlanner {

        private int updateCalls;

        @Override
        public DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence) {
            updateCalls++;
            return new DiagnosisPlan(
                    "updated",
                    List.of(Hypothesis.open("H1", "log errors", 0.7)),
                    List.of(new DiagnosisStep("S1", "read logs", "H1",
                            List.of("LogQuery"), StepStatus.RUNNING, "")));
        }
    }

    private static final class MissingInputPlanner
            implements com.anthropic.cclc.application.diagnosis.DiagnosisPlanner {

        @Override
        public DiagnosisPlan createPlan(DiagnosisCase diagnosisCase) {
            return new DiagnosisPlan(
                    "need scope",
                    List.of(Hypothesis.open("H1", "missing scope", 0.1)),
                    List.of(new DiagnosisStep("S1", "ask trace", "H1",
                            List.of("LogQuery"), StepStatus.PENDING, "")),
                    List.of("traceId", "timeWindow"));
        }

        @Override
        public DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence) {
            return createPlan(diagnosisCase);
        }
    }

    private static final class FakeReporter
            implements com.anthropic.cclc.application.diagnosis.DiagnosisReporter {

        @Override
        public DiagnosisReport report(DiagnosisCase diagnosisCase) {
            return new DiagnosisReport("summary", List.of(), List.of(), List.of(), 0.1, true);
        }
    }

    private enum StepGuard {
        OBSERVE(com.anthropic.cclc.application.diagnosis.PlanGuardMode.OBSERVE),
        DENY(com.anthropic.cclc.application.diagnosis.PlanGuardMode.DENY);

        private final com.anthropic.cclc.application.diagnosis.PlanGuardMode mode;

        StepGuard(com.anthropic.cclc.application.diagnosis.PlanGuardMode mode) {
            this.mode = mode;
        }

        private com.anthropic.cclc.application.diagnosis.PlanGuardMode mode() {
            return mode;
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
