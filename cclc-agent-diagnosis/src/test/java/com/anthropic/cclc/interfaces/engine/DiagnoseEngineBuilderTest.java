package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.application.diagnosis.DiagnosisPlanner;
import com.anthropic.cclc.domain.agent.AgentBudget;
import com.anthropic.cclc.domain.diagnosis.DiagnosisCase;
import com.anthropic.cclc.domain.diagnosis.DiagnosisPlan;
import com.anthropic.cclc.domain.diagnosis.DiagnosisStep;
import com.anthropic.cclc.domain.diagnosis.Evidence;
import com.anthropic.cclc.domain.diagnosis.Hypothesis;
import com.anthropic.cclc.domain.diagnosis.StepStatus;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnosisToolBackends;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnosisToolPolicy;
import com.anthropic.cclc.infrastructure.tools.support.HttpReader;
import com.anthropic.cclc.testsupport.StubLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnoseEngineBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requiresLlmClient() {
        assertThatThrownBy(() -> DiagnoseEngineBuilder.create().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm");
    }

    @Test
    void buildsEngineWithInjectedToolsAndBudget() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("should not be called"));
        DiagnoseEngine engine = DiagnoseEngineBuilder.create()
                .llm(llm)
                .tools(new ToolRegistry())
                .budget(AgentBudget.of(0, 0, 0))
                .build();
        AtomicInteger exit = new AtomicInteger(Integer.MIN_VALUE);
        List<String> lines = new ArrayList<>();

        engine.runStream(RunRequest.builder()
                .workingDir(".")
                .userMessage("hi")
                .sessionId("s-builder")
                .build(), lines::add, exit::set);

        assertThat(exit).hasValue(0);
        assertThat(llm.capturedRequests()).isEmpty();
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("diagnosis_report"));
    }

    @Test
    void buildsEngineWithPlanner() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        DiagnoseEngine engine = DiagnoseEngineBuilder.create()
                .llm(llm)
                .planner(new FakePlanner())
                .build();
        List<String> lines = new ArrayList<>();

        engine.runStream(RunRequest.builder()
                .workingDir(".")
                .userMessage("hi")
                .sessionId("s-builder")
                .build(), lines::add, ignored -> {
                });

        assertThat(lines.stream().map(this::typeOf).toList()).contains("diagnosis_plan");
    }

    @Test
    void buildsEngineWithDiagnosisToolBackends() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("log-1"),
                        "LogQuery",
                        "{\"traceId\":\"trace-1\"}"))))
                .enqueue(AiMessage.text("done"));
        DiagnosisToolBackends backends = DiagnosisToolBackends.builder()
                .logQuery(request -> "log line")
                .build();
        DiagnoseEngine engine = DiagnoseEngineBuilder.create()
                .llm(llm)
                .toolBackends(backends)
                .build();
        List<String> lines = new ArrayList<>();

        engine.runStream(RunRequest.builder()
                .workingDir(".")
                .userMessage("hi")
                .sessionId("s-tools")
                .build(), lines::add, ignored -> {
                });

        assertThat(lines).anySatisfy(line -> assertThat(line).contains("log line"));
    }

    @Test
    void appliesToolPolicyAfterBackendAssembly() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("http-1"),
                        "HttpGet",
                        "{\"url\":\"https://evil.local/health\"}"))))
                .enqueue(AiMessage.text("done"));
        DiagnosisToolBackends backends = DiagnosisToolBackends.builder()
                .http((url, headers, timeout) -> new HttpReader.HttpResponseView(200, "ok"))
                .build();
        DiagnoseEngine engine = DiagnoseEngineBuilder.create()
                .llm(llm)
                .toolBackends(backends)
                .toolPolicy(new DiagnosisToolPolicy(Set.of("svc.local"), Set.of()))
                .build();
        List<String> lines = new ArrayList<>();

        engine.runStream(RunRequest.builder()
                .workingDir(".")
                .userMessage("hi")
                .sessionId("s-policy")
                .build(), lines::add, ignored -> {
                });

        assertThat(lines).anySatisfy(line -> assertThat(line).contains("not allowlisted"));
    }

    @Test
    void includesPromptPackContentInSystemPrompt(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order.md"), "Qpon order diagnosis SOP");
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        DiagnoseEngine engine = DiagnoseEngineBuilder.create()
                .llm(llm)
                .promptPacks(dir)
                .build();

        engine.runStream(RunRequest.builder()
                .workingDir(".")
                .userMessage("hi")
                .sessionId("s-prompt")
                .build(), ignored -> {
                }, ignored -> {
                });

        assertThat(llm.capturedRequests().get(0).systemPrompt())
                .contains("Qpon order diagnosis SOP");
    }

    @Test
    void skillsAddsCatalogToSystemPromptAndRegistersSkillTool(@TempDir Path skillsRoot) throws IOException {
        writeSkill(skillsRoot.resolve("es-slow-query"), """
                ---
                description: Diagnose slow ES queries when took or P99 spikes.
                ---
                # ES Slow Query
                Use profile output.
                """);
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("skill-1"),
                        "Skill",
                        "{\"skill\":\"es-slow-query\"}"))))
                .enqueue(AiMessage.text("done"));
        DiagnoseEngine engine = DiagnoseEngineBuilder.create()
                .llm(llm)
                .skills(skillsRoot)
                .build();
        List<String> lines = new ArrayList<>();

        engine.runStream(RunRequest.builder()
                .workingDir(".")
                .userMessage("ES query timeout")
                .sessionId("s-skill")
                .build(), lines::add, ignored -> {
                });

        assertThat(llm.capturedRequests().get(0).systemPrompt())
                .contains("## skills")
                .contains("es-slow-query: Diagnose slow ES queries");
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains("# Skill: es-slow-query")
                .contains("# ES Slow Query"));
    }

    @Test
    void skillsFailsFastWhenRootIsInvalid(@TempDir Path dir) {
        Path missing = dir.resolve("missing");

        assertThatThrownBy(() -> DiagnoseEngineBuilder.create().skills(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skills root");
    }

    @Test
    void structuredDiagnosisWiresPlannerAndReporter() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-1"), "update_plan", planJson()))))
                .enqueue(AiMessage.text("planned"))
                .enqueue(AiMessage.text("done"))
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("report-1"), "submit_report", reportJson()))))
                .enqueue(AiMessage.text("reported"));
        DiagnoseEngine engine = DiagnoseEngineBuilder.create()
                .llm(llm)
                .structuredDiagnosis()
                .build();
        List<String> lines = new ArrayList<>();

        engine.runStream(RunRequest.builder()
                .workingDir(".")
                .userMessage("hi")
                .sessionId("s-structured")
                .build(), lines::add, ignored -> {
                });

        assertThat(lines.stream().map(this::typeOf).toList())
                .contains("diagnosis_plan", "diagnosis_report");
    }

    @Test
    void structuredDiagnosisRequiresLlmFirst() {
        assertThatThrownBy(() -> DiagnoseEngineBuilder.create().structuredDiagnosis())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm");
    }

    private String typeOf(String line) {
        try {
            return mapper.readTree(line).path("type").asText();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void writeSkill(Path directory, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), content);
    }

    private static String planJson() {
        return """
                {
                  "problemStatement": "hi",
                  "hypotheses": [{"id": "H1", "statement": "unknown", "confidence": 0.1}],
                  "steps": [{
                    "id": "S1",
                    "goal": "inspect",
                    "hypothesisId": "H1",
                    "allowedTools": ["LogQuery"],
                    "status": "PENDING"
                  }]
                }
                """;
    }

    private static String reportJson() {
        return """
                {
                  "summary": "needs more evidence",
                  "rootCauseCandidates": [],
                  "keyEvidenceIds": [],
                  "recommendedActions": ["collect logs"],
                  "missingInformation": [],
                  "confidence": 0.1,
                  "needHumanCheck": true
                }
                """;
    }

    private static final class FakePlanner implements DiagnosisPlanner {

        @Override
        public DiagnosisPlan createPlan(DiagnosisCase diagnosisCase) {
            return new DiagnosisPlan(
                    "hi",
                    List.of(Hypothesis.open("H1", "入口服务报错", 0.4)),
                    List.of(new DiagnosisStep("S1", "查日志", "H1",
                            List.of("LogQuery"), StepStatus.RUNNING, "")));
        }

        @Override
        public DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence) {
            return createPlan(diagnosisCase);
        }
    }
}
