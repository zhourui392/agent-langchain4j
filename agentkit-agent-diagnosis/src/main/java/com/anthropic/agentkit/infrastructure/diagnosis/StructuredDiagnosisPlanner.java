package com.anthropic.agentkit.infrastructure.diagnosis;

import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.diagnosis.DiagnosisPlanner;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisStep;
import com.anthropic.agentkit.domain.diagnosis.Evidence;
import com.anthropic.agentkit.domain.diagnosis.Hypothesis;
import com.anthropic.agentkit.domain.diagnosis.StepStatus;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.infrastructure.tools.StructuredOutputTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM planner that receives plans through the kernel structured-output tool.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class StructuredDiagnosisPlanner implements DiagnosisPlanner {

    private static final Logger log = LoggerFactory.getLogger(StructuredDiagnosisPlanner.class);

    private static final String TOOL_NAME = "update_plan";
    private static final String PLAN_SCHEMA = """
            {"type":"object","properties":{\
            "problemStatement":{"type":"string"},\
            "hypotheses":{"type":"array"},\
            "steps":{"type":"array"},\
            "missingInputs":{"type":"array"}\
            },"required":["problemStatement","hypotheses","steps"]}""";
    private static final String SYSTEM_PROMPT =
            "Create or update a diagnosis plan by calling the update_plan tool.";

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public StructuredDiagnosisPlanner(LlmClient llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    @Override
    public DiagnosisPlan createPlan(DiagnosisCase diagnosisCase) {
        long startNs = System.nanoTime();
        AtomicReference<Map<String, Object>> acceptedPlan = new AtomicReference<>();
        ToolRegistry tools = new ToolRegistry().register(new StructuredOutputTool(
                TOOL_NAME, "Submit a structured diagnosis plan", PLAN_SCHEMA, acceptedPlan::set));

        Conversation conversation = new Conversation(SessionId.fresh());
        conversation.append(UserMessage.of("Create a diagnosis plan for: " + diagnosisCase.question()));
        new AgentExecutor(llm, tools).run(conversation, new CancellationToken(),
                com.anthropic.agentkit.application.AgentEventListener.NO_OP, SYSTEM_PROMPT).join();
        DiagnosisPlan plan = toPlan(acceptedPlan.get());
        log.info("diagnosis plan created: caseId={}, steps={}, hypotheses={}, missingInputs={}, durationMs={}",
                diagnosisCase.caseId(), plan.steps().size(), plan.hypotheses().size(),
                plan.missingInputs().size(), elapsedMs(startNs));
        return plan;
    }

    @Override
    public DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence) {
        log.info("diagnosis plan update requested: caseId={}, evidenceId={}",
                diagnosisCase.caseId(), evidence.id());
        return createPlan(diagnosisCase);
    }

    private DiagnosisPlan toPlan(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalStateException("planner did not call " + TOOL_NAME);
        }
        PlanDto dto = mapper.convertValue(payload, PlanDto.class);
        return new DiagnosisPlan(
                dto.problemStatement(),
                toHypotheses(dto.hypotheses()),
                toSteps(dto.steps()),
                safeList(dto.missingInputs()));
    }

    private static List<Hypothesis> toHypotheses(List<HypothesisDto> items) {
        return safeList(items).stream()
                .map(item -> Hypothesis.open(item.id(), item.statement(), item.confidence()))
                .toList();
    }

    private static List<DiagnosisStep> toSteps(List<StepDto> items) {
        return safeList(items).stream()
                .map(item -> new DiagnosisStep(
                        item.id(),
                        item.goal(),
                        item.hypothesisId(),
                        safeList(item.allowedTools()),
                        stepStatus(item.status()),
                        item.resultSummary()))
                .toList();
    }

    private static StepStatus stepStatus(String status) {
        return status == null || status.isBlank()
                ? StepStatus.PENDING
                : StepStatus.valueOf(status);
    }

    private static <T> List<T> safeList(List<T> items) {
        return items == null ? List.of() : items;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private record PlanDto(String problemStatement, List<HypothesisDto> hypotheses, List<StepDto> steps,
                           List<String> missingInputs) {
    }

    private record HypothesisDto(String id, String statement, double confidence) {
    }

    private record StepDto(String id, String goal, String hypothesisId, List<String> allowedTools,
                           String status, String resultSummary) {
    }
}
