package com.anthropic.agentkit.application.diagnosis;

import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentSpec;
import com.anthropic.agentkit.domain.agent.SubAgentRuntime;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one diagnosis sub-task through the kernel {@code Task} tool.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class DiagnosisTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisTaskRunner.class);

    private final SubAgentRuntime runtime;
    private final AgentSpec taskSpec;

    public DiagnosisTaskRunner(SubAgentRuntime runtime, AgentSpec taskSpec) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.taskSpec = Objects.requireNonNull(taskSpec, "taskSpec");
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
        long startNs = System.nanoTime();
        log.info("diagnosis task started: taskType={}, hypothesisId={}",
                request.taskType(), request.hypothesisId());
        AgentRunResult childResult = runtime.spawn(
                        taskSpec, formatPrompt(request), context)
                .result().toCompletableFuture().join();
        ToolResult result = toToolResult(childResult);
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        if (!result.success()) {
            log.warn("diagnosis task failed: taskType={}, hypothesisId={}, durationMs={}",
                    request.taskType(), request.hypothesisId(), durationMs);
        }
        log.info("diagnosis task completed: taskType={}, hypothesisId={}, success={}, resultChars={}, durationMs={}",
                request.taskType(), request.hypothesisId(), result.success(), result.content().length(), durationMs);
        return result;
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

    private static ToolResult toToolResult(AgentRunResult result) {
        return switch (result.stopReason()) {
            case MODEL_COMPLETED, TERMINAL_TOOL -> ToolResult.ok(result.finalMessage().text());
            case CANCELLED -> ToolResult.of(ToolResultStatus.CANCELLED, "sub-agent cancelled");
            case TIMED_OUT -> ToolResult.of(ToolResultStatus.TIMEOUT, "sub-agent timed out");
            case BUDGET_EXHAUSTED -> ToolResult.of(
                    ToolResultStatus.BUDGET_EXHAUSTED, "sub-agent budget exhausted");
            default -> ToolResult.error("sub-agent stopped: " + result.stopReason());
        };
    }
}
