package com.anthropic.cclc.application.diagnosis;

import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.SubAgentTool;

import java.util.Map;
import java.util.Objects;

/**
 * Runs one diagnosis sub-task through the kernel {@code Task} tool.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class DiagnosisTaskRunner {

    private final SubAgentTool taskTool;

    public DiagnosisTaskRunner(LlmClient llm, ToolRegistry childTools) {
        this.taskTool = new SubAgentTool(
                Objects.requireNonNull(llm, "llm"),
                Objects.requireNonNull(childTools, "childTools"));
    }

    /**
     * Executes a structured diagnosis sub-task in an isolated child conversation.
     *
     * @param request structured diagnosis task request
     * @param context execution context inherited from the parent run
     * @return child task result
     */
    public ToolResult run(DiagnosisTaskRequest request, ExecutionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return taskTool.execute(ToolArguments.of(Map.of("prompt", formatPrompt(request))), context);
    }

    private static String formatPrompt(DiagnosisTaskRequest request) {
        return String.join("\n",
                "Run a read-only diagnosis sub-task.",
                "taskType: " + request.taskType(),
                "hypothesisId: " + request.hypothesisId(),
                "goal: " + request.goal(),
                "scope: " + request.scopeSummary(),
                "Return a concise evidence-backed finding.");
    }
}
