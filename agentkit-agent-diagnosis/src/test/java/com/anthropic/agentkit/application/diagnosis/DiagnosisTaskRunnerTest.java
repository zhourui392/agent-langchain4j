package com.anthropic.agentkit.application.diagnosis;

import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisTaskRunnerTest {

    @Test
    void delegatesStructuredDiagnosisTaskToKernelSubAgent() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("log confirms timeout"));
        DiagnosisTaskRunner runner = new DiagnosisTaskRunner(llm, new ToolRegistry());

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
}
