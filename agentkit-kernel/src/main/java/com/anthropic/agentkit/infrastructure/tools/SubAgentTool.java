package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.application.InteractivePrompter;
import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentSpec;
import com.anthropic.agentkit.domain.agent.ModelTier;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.agent.SubAgentHandle;
import com.anthropic.agentkit.domain.agent.SubAgentLimits;
import com.anthropic.agentkit.domain.agent.SubAgentRuntime;
import com.anthropic.agentkit.domain.agent.ToolCapabilitySet;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmClientSelector;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.infrastructure.agent.DefaultSubAgentRuntime;
import com.anthropic.agentkit.infrastructure.permission.ReadOnlyPermissionPolicy;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Synchronous tool adapter over the generic, lifecycle-aware {@link SubAgentRuntime}. */
public final class SubAgentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SubAgentTool.class);
    private static final String DEFAULT_NAME = "Task";
    private static final String DEFAULT_DESCRIPTION =
            "Launch a bounded child agent for one delegated task and return its final response.";
    private static final InteractivePrompter REJECTING_PROMPTER = (invocation, tool) -> {
        throw new IllegalStateException("child agent has no interactive approval channel");
    };

    private final String toolName;
    private final String toolDescription;
    private final SubAgentRuntime runtime;
    private final AgentSpec spec;
    private final boolean readOnly;

    public SubAgentTool(SubAgentRuntime runtime, AgentSpec spec) {
        this(DEFAULT_NAME, DEFAULT_DESCRIPTION, runtime, spec, false);
    }

    public SubAgentTool(
            String toolName, String toolDescription,
            SubAgentRuntime runtime, AgentSpec spec, boolean readOnly) {
        this.toolName = requireText(toolName, "toolName");
        this.toolDescription = requireText(toolDescription, "toolDescription");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.readOnly = readOnly;
    }

    public SubAgentTool(LlmClient llm, ToolRegistry childTools) {
        this(llm, childTools, AgentBudget.unlimited(), AgentRunLimits.defaults());
    }

    public SubAgentTool(LlmClient llm, ToolRegistry childTools,
                        AgentBudget childBudget, AgentRunLimits childLimits) {
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(childTools, "childTools");
        Objects.requireNonNull(childBudget, "childBudget");
        Objects.requireNonNull(childLimits, "childLimits");
        this.toolName = DEFAULT_NAME;
        this.toolDescription = DEFAULT_DESCRIPTION;
        this.runtime = legacyRuntime(llm, childTools);
        this.spec = legacySpec(childTools, childBudget, childLimits);
        this.readOnly = true;
    }

    @Override
    public String name() {
        return toolName;
    }

    @Override
    public String description() {
        return toolDescription;
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}},"
                + "\"required\":[\"prompt\"]}";
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext context) {
        long startNs = System.nanoTime();
        String prompt = args.getString("prompt");
        log.info("sub-agent started: agentId={}, promptChars={}", spec.id(), prompt.length());
        try {
            SubAgentHandle handle = runtime.spawn(spec, prompt, context);
            AgentRunResult result = handle.result().toCompletableFuture().join();
            log.info("sub-agent stopped: agentId={}, childRunId={}, stopReason={}, durationMs={}",
                    spec.id(), result.runId(), result.stopReason(), elapsedMs(startNs));
            return toToolResult(result);
        } catch (CompletionException failure) {
            return ToolResult.error(failureMessage(context, failure));
        }
    }

    private static DefaultSubAgentRuntime legacyRuntime(
            LlmClient llm, ToolRegistry childTools) {
        PermissionService permissions = new PermissionService(
                new ReadOnlyPermissionPolicy(), REJECTING_PROMPTER, PermissionMode.BYPASS);
        return new DefaultSubAgentRuntime(
                LlmClientSelector.fixed(llm), childTools, permissions, SubAgentLimits.defaults());
    }

    private static AgentSpec legacySpec(
            ToolRegistry childTools, AgentBudget budget, AgentRunLimits limits) {
        return new AgentSpec(
                AgentId.of("task"), "Complete the delegated task within the granted capabilities.",
                ToolCapabilitySet.copyOf(childTools.names()), ModelTier.DEFAULT,
                budget, limits, Optional.empty());
    }

    private static ToolResult toToolResult(AgentRunResult result) {
        if (result.stopReason() == StopReason.MODEL_COMPLETED
                || result.stopReason() == StopReason.TERMINAL_TOOL) {
            String content = result.structuredOutput()
                    .map(Object::toString)
                    .orElseGet(() -> result.finalMessage().text());
            return ToolResult.ok(content);
        }
        return stoppedResult(result.stopReason());
    }

    private static ToolResult stoppedResult(StopReason reason) {
        return switch (reason) {
            case CANCELLED -> ToolResult.of(ToolResultStatus.CANCELLED, "sub-agent cancelled");
            case TIMED_OUT -> ToolResult.of(ToolResultStatus.TIMEOUT, "sub-agent timed out");
            case BUDGET_EXHAUSTED -> ToolResult.of(
                    ToolResultStatus.BUDGET_EXHAUSTED, "sub-agent budget exhausted");
            default -> ToolResult.error("sub-agent stopped: " + reason);
        };
    }

    private static String failureMessage(
            ExecutionContext context, CompletionException failure) {
        if (context.cancellation().isCancelled()) {
            return "sub-agent cancelled";
        }
        Throwable cause = failure.getCause();
        return "sub-agent failed: " + (cause == null ? failure.getMessage() : cause.getMessage());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
