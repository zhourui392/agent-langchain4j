package com.anthropic.agentkit.infrastructure.coding;

import com.anthropic.agentkit.application.coding.CodingPatcher;
import com.anthropic.agentkit.domain.coding.CodingPlan;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.coding.FileChange;
import com.anthropic.agentkit.domain.coding.FileChangeType;
import com.anthropic.agentkit.domain.coding.Patch;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.infrastructure.agent.StructuredAgent;
import com.anthropic.agentkit.infrastructure.agent.TerminalToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coder role: delegates the constrained-turn boilerplate to the kernel
 * {@link StructuredAgent} and owns only the role config plus payload-to-VO
 * mapping. Unlike the planner, it carries injected {@code codingTools}
 * (read/write capability) so the model can mutate the working tree before
 * submitting the structured patch.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-20
 */
public final class StructuredCodingPatcher implements CodingPatcher {

    private static final Logger log = LoggerFactory.getLogger(StructuredCodingPatcher.class);

    private static final String TOOL_NAME = "submit_patch";
    private static final String PATCH_SCHEMA = """
            {"type":"object","properties":{\
            "summary":{"type":"string"},\
            "changes":{"type":"array"}\
            },"required":["summary"]}""";
    private static final String SYSTEM_PROMPT =
            "Implement the plan by editing files with the available tools, "
                    + "then report the result by calling the submit_patch tool.";
    private static final TerminalToolSpec PATCH_OUTPUT = new TerminalToolSpec(
            TOOL_NAME, "Submit the structured patch produced for the plan", PATCH_SCHEMA);

    private final LlmClient llm;
    private final List<Tool> codingTools;
    private final ObjectMapper mapper = new ObjectMapper();

    public StructuredCodingPatcher(LlmClient llm, List<Tool> codingTools) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.codingTools = List.copyOf(Objects.requireNonNull(codingTools, "codingTools"));
    }

    @Override
    public Patch producePatch(CodingTask task, CodingPlan plan) {
        long startNs = System.nanoTime();
        StructuredAgent agent = new StructuredAgent(llm, SYSTEM_PROMPT, PATCH_OUTPUT, codingTools);
        Map<String, Object> payload = agent.run(buildTask(task, plan), ExecutionContext.at(Path.of(".")));
        Patch patch = toPatch(payload);
        log.info("patch produced: taskId={}, changes={}, durationMs={}",
                task.taskId(), patch.changes().size(), elapsedMs(startNs));
        return patch;
    }

    private static String buildTask(CodingTask task, CodingPlan plan) {
        return "Implement the requirement: " + task.requirement()
                + "\nPlan: " + plan.problemStatement()
                + "\nTask items: " + plan.tasks().size();
    }

    private Patch toPatch(Map<String, Object> payload) {
        PatchDto dto = mapper.convertValue(payload, PatchDto.class);
        return new Patch(dto.summary(), toChanges(dto.changes()));
    }

    private static List<FileChange> toChanges(List<FileChangeDto> items) {
        return safeList(items).stream()
                .map(item -> new FileChange(item.path(), changeType(item.changeType()), item.diff()))
                .toList();
    }

    private static FileChangeType changeType(String changeType) {
        return changeType == null || changeType.isBlank()
                ? FileChangeType.EDIT
                : FileChangeType.valueOf(changeType);
    }

    private static <T> List<T> safeList(List<T> items) {
        return items == null ? List.of() : items;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private record PatchDto(String summary, List<FileChangeDto> changes) {
    }

    private record FileChangeDto(String path, String changeType, String diff) {
    }
}
