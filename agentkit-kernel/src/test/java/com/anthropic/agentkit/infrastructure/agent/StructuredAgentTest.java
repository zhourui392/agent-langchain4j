package com.anthropic.agentkit.infrastructure.agent;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.AgentSpec;
import com.anthropic.agentkit.domain.agent.ModelTier;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.TerminalToolSpec;
import com.anthropic.agentkit.domain.agent.ToolCapabilitySet;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the structured-agent kernel SPI: take a system prompt + terminal tool spec,
 * run a constrained turn, return the structured payload (or fail with a clear
 * exception when the agent never called the terminal tool).
 *
 * @author zhourui(V33215020)
 * @since 2026-06-19
 */
class StructuredAgentTest {

    private static final String SCHEMA = """
            {"type":"object","properties":{\
            "problemStatement":{"type":"string"}\
            },"required":["problemStatement"]}""";
    private static final String TERMINAL_TOOL = "submit_plan";
    private static final TerminalToolSpec OUTPUT = new TerminalToolSpec(
            TERMINAL_TOOL, "Submit a plan", SCHEMA);

    @Test
    void emitsPayloadWhenAgentCallsTerminalTool() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(AiMessage.of("", List.of(new ToolUseRequest(
                        new ToolUseId("t-1"), TERMINAL_TOOL,
                        "{\"problemStatement\":\"db slow\"}"))));
        StructuredAgent agent = new StructuredAgent(llm, spec(ToolCapabilitySet.none()), List.of());

        Map<String, Object> payload = agent.run("Make a plan", context());

        assertThat(payload).containsEntry("problemStatement", "db slow");
        assertThat(llm.capturedRequests()).hasSize(1);
    }

    @Test
    void throwsWhenTerminalToolNeverCalled() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("I won't"));
        StructuredAgent agent = new StructuredAgent(llm, spec(ToolCapabilitySet.none()), List.of());

        assertThatThrownBy(() -> agent.run("Make a plan", context()))
                .isInstanceOf(StructuredOutputMissingException.class)
                .hasMessageContaining(TERMINAL_TOOL);
    }

    @Test
    void usesProvidedRunContextForDomainTools(@TempDir Path workspace) {
        AtomicReference<ExecutionContext> received = new AtomicReference<>();
        Tool inspect = contextRecordingTool(received);
        StubLlmClient llm = new StubLlmClient()
                .enqueue(AiMessage.of("", List.of(new ToolUseRequest(
                        new ToolUseId("inspect-1"), "Inspect", "{}"))))
                .enqueue(AiMessage.of("", List.of(new ToolUseRequest(
                        new ToolUseId("terminal-1"), TERMINAL_TOOL,
                        "{\"problemStatement\":\"scoped\"}"))));
        StructuredAgent agent = new StructuredAgent(
                llm, spec(ToolCapabilitySet.of("Inspect")), List.of(inspect));
        CancellationToken cancellation = new CancellationToken();
        AgentRunContext context = AgentRunContext.of(
                RunId.of("structured-run"), SessionId.of("structured-session"),
                WorkspaceId.of("structured-workspace"), workspace, cancellation,
                AgentBudget.unlimited());

        agent.run("Inspect then plan", context);

        assertThat(received.get().cwd()).isEqualTo(workspace);
        assertThat(received.get().cancellation()).isSameAs(cancellation);
        assertThat(received.get().runId()).isEqualTo(RunId.of("structured-run"));
    }

    private static AgentRunContext context() {
        return AgentRunContext.at(Paths.get(System.getProperty("user.dir")));
    }

    private static AgentSpec spec(ToolCapabilitySet tools) {
        return new AgentSpec(
                AgentId.of("planner"), "Plan it.", tools, ModelTier.DEFAULT,
                AgentBudget.unlimited(), AgentRunLimits.defaults(), Optional.of(OUTPUT));
    }

    private static Tool contextRecordingTool(AtomicReference<ExecutionContext> received) {
        return new Tool() {
            @Override public String name() { return "Inspect"; }
            @Override public String description() { return "capture run context"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean isReadOnly() { return true; }
            @Override public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
                received.set(ctx);
                return ToolResult.ok("inspected");
            }
        };
    }
}
