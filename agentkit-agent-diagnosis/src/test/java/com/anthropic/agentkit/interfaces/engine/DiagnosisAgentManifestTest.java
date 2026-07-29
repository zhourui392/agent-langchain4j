package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.agent.AgentManifest;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisAgentManifestTest {

    @Test
    void manifestCapabilitiesMatchConfiguredDiagnosisTools() {
        ToolRegistry tools = new ToolRegistry()
                .register(FakeTool.returning("LogQuery", "evidence"));
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));

        AgentManifest<RunRequest, RunSummary> manifest = DiagnoseEngineBuilder.create()
                .llm(llm)
                .tools(tools)
                .buildManifest();

        assertThat(manifest.id().value()).isEqualTo("diagnosis");
        assertThat(manifest.capabilities().allowedTools().names()).containsExactly("LogQuery");
        assertThat(manifest.capabilities().terminalTools()).isEmpty();
        assertThat(manifest.entryPoint().requestType()).isEqualTo(RunRequest.class);
        assertThat(manifest.entryPoint().resultType()).isEqualTo(RunSummary.class);
        manifest.entryPoint().close();
    }
}
