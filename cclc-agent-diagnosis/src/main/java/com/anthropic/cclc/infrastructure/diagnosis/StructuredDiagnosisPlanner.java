package com.anthropic.cclc.infrastructure.diagnosis;

import com.anthropic.cclc.application.AgentExecutor;
import com.anthropic.cclc.application.diagnosis.DiagnosisPlanner;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.diagnosis.DiagnosisCase;
import com.anthropic.cclc.domain.diagnosis.DiagnosisPlan;
import com.anthropic.cclc.domain.diagnosis.DiagnosisStep;
import com.anthropic.cclc.domain.diagnosis.Evidence;
import com.anthropic.cclc.domain.diagnosis.Hypothesis;
import com.anthropic.cclc.domain.diagnosis.StepStatus;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.infrastructure.tools.StructuredOutputTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM planner that receives plans through the kernel structured-output tool.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class StructuredDiagnosisPlanner implements DiagnosisPlanner {

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
        AtomicReference<Map<String, Object>> acceptedPlan = new AtomicReference<>();
        ToolRegistry tools = new ToolRegistry().register(new StructuredOutputTool(
                TOOL_NAME, "Submit a structured diagnosis plan", PLAN_SCHEMA, acceptedPlan::set));

        Conversation conversation = new Conversation(SessionId.fresh());
        conversation.append(UserMessage.of("Create a diagnosis plan for: " + diagnosisCase.question()));
        new AgentExecutor(llm, tools).run(conversation, new CancellationToken(),
                com.anthropic.cclc.application.AgentEventListener.NO_OP, SYSTEM_PROMPT).join();
        return toPlan(acceptedPlan.get());
    }

    @Override
    public DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence) {
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

    private record PlanDto(String problemStatement, List<HypothesisDto> hypotheses, List<StepDto> steps,
                           List<String> missingInputs) {
    }

    private record HypothesisDto(String id, String statement, double confidence) {
    }

    private record StepDto(String id, String goal, String hypothesisId, List<String> allowedTools,
                           String status, String resultSummary) {
    }
}
