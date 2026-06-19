package com.anthropic.agentkit.infrastructure.agent;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

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
                        "{\"problemStatement\":\"db slow\"}"))))
                .enqueue(AiMessage.text("done"));
        StructuredAgent agent = new StructuredAgent(llm, "Plan it.", OUTPUT, List.of());

        Map<String, Object> payload = agent.run("Make a plan", context());

        assertThat(payload).containsEntry("problemStatement", "db slow");
    }

    @Test
    void throwsWhenTerminalToolNeverCalled() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("I won't"));
        StructuredAgent agent = new StructuredAgent(llm, "Plan it.", OUTPUT, List.of());

        assertThatThrownBy(() -> agent.run("Make a plan", context()))
                .isInstanceOf(StructuredOutputMissingException.class)
                .hasMessageContaining(TERMINAL_TOOL);
    }

    private static ExecutionContext context() {
        return ExecutionContext.at(Paths.get(System.getProperty("user.dir")));
    }
}
