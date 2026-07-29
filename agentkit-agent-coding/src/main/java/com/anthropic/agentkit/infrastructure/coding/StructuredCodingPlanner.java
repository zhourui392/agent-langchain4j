package com.anthropic.agentkit.infrastructure.coding;

import com.anthropic.agentkit.application.coding.CodingPlanner;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.coding.CodingPlan;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.coding.TaskItem;
import com.anthropic.agentkit.domain.coding.TaskItemStatus;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.infrastructure.agent.StructuredAgent;
import com.anthropic.agentkit.infrastructure.agent.TerminalToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Planner role: delegates the constrained-turn boilerplate to the kernel
 * {@link StructuredAgent} and owns only the role config plus payload-to-VO
 * mapping. {@code domainTools} is empty — the planner reasons, it does not act.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-20
 */
public final class StructuredCodingPlanner implements CodingPlanner {

    private static final Logger log = LoggerFactory.getLogger(StructuredCodingPlanner.class);

    private static final String TOOL_NAME = "update_plan";
    private static final String PLAN_SCHEMA = """
            {"type":"object","properties":{\
            "problemStatement":{"type":"string"},\
            "tasks":{"type":"array"}\
            },"required":["problemStatement"]}""";
    private static final String SYSTEM_PROMPT =
            "Decompose the coding requirement into an ordered plan by calling the update_plan tool.";
    private static final TerminalToolSpec PLAN_OUTPUT = new TerminalToolSpec(
            TOOL_NAME, "Submit a structured coding plan", PLAN_SCHEMA);

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public StructuredCodingPlanner(LlmClient llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    @Override
    public CodingPlan createPlan(CodingTask task, AgentRunContext context) {
        long startNs = System.nanoTime();
        StructuredAgent agent = new StructuredAgent(llm, SYSTEM_PROMPT, PLAN_OUTPUT, List.of());
        Map<String, Object> payload = agent.run(
                "Create a coding plan for: " + task.requirement(),
                context);
        CodingPlan plan = toPlan(payload);
        log.info("coding plan created: taskId={}, tasks={}, durationMs={}",
                task.taskId(), plan.tasks().size(), elapsedMs(startNs));
        return plan;
    }

    private CodingPlan toPlan(Map<String, Object> payload) {
        PlanDto dto = mapper.convertValue(payload, PlanDto.class);
        return new CodingPlan(dto.problemStatement(), toTasks(dto.tasks()));
    }

    private static List<TaskItem> toTasks(List<TaskItemDto> items) {
        return safeList(items).stream()
                .map(item -> new TaskItem(item.id(), item.goal(), safeList(item.files()), taskStatus(item.status())))
                .toList();
    }

    private static TaskItemStatus taskStatus(String status) {
        return status == null || status.isBlank()
                ? TaskItemStatus.PENDING
                : TaskItemStatus.valueOf(status);
    }

    private static <T> List<T> safeList(List<T> items) {
        return items == null ? List.of() : items;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private record PlanDto(String problemStatement, List<TaskItemDto> tasks) {
    }

    private record TaskItemDto(String id, String goal, List<String> files, String status) {
    }
}
