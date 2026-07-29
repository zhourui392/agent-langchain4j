package com.anthropic.agentkit.application.diagnosis;

import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.AgentSpec;
import com.anthropic.agentkit.domain.agent.ModelTier;
import com.anthropic.agentkit.domain.agent.SubAgentLimits;
import com.anthropic.agentkit.domain.agent.ToolCapabilitySet;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.port.LlmClientSelector;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.agent.DefaultSubAgentRuntime;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisTaskRunnerTest {

    @Test
    void delegatesStructuredDiagnosisTaskToKernelSubAgent() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("log confirms timeout"));
        DefaultSubAgentRuntime runtime = new DefaultSubAgentRuntime(
                LlmClientSelector.fixed(llm), new ToolRegistry(),
                PermissionService.bypassing(), SubAgentLimits.defaults());
        DiagnosisTaskRunner runner = new DiagnosisTaskRunner(runtime, taskSpec());

        ToolResult result = runner.run(new DiagnosisTaskRequest(
                        "LOG_TRACE",
                        "H1",
                        "verify whether order-service timed out",
                        "prod order-service 10:00-10:15 trace=abc"),
                ExecutionContext.of(Path.of("."), new CancellationToken()));

        assertThat(result).isEqualTo(ToolResult.ok("log confirms timeout"));
        assertThat(llm.capturedRequests()).hasSize(1);
        String prompt = llm.capturedRequests().get(0).messages().get(0).text();
        assertThat(prompt).contains("taskType: LOG_TRACE");
        assertThat(prompt).contains("hypothesisId: H1");
        assertThat(prompt).contains("goal: verify whether order-service timed out");
        assertThat(prompt).contains("scope: prod order-service 10:00-10:15 trace=abc");
    }

    private static AgentSpec taskSpec() {
        return new AgentSpec(
                AgentId.of("diagnosis-task"), "Investigate one diagnosis question.",
                ToolCapabilitySet.none(), ModelTier.DEFAULT,
                AgentBudget.unlimited(), AgentRunLimits.defaults(), Optional.empty());
    }
}
