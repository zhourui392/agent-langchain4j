package com.anthropic.agentkit.application.agent;

import com.anthropic.agentkit.domain.agent.AgentManifest;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.coding.CodingStatus;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.interfaces.engine.CodingEngineBuilder;
import com.anthropic.agentkit.interfaces.engine.CodingRequest;
import com.anthropic.agentkit.interfaces.engine.DiagnoseEngineBuilder;
import com.anthropic.agentkit.interfaces.engine.ExitReason;
import com.anthropic.agentkit.interfaces.engine.RunRequest;
import com.anthropic.agentkit.interfaces.engine.RunSummary;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRegistryModuleIntegrationTest {

    @Test
    void oneHostRegistryDispatchesDiagnosisAndCodingAgents() {
        AgentManifest<RunRequest, RunSummary> diagnosis = diagnosisManifest();
        AgentManifest<CodingRequest, CodingTask> coding = codingManifest();
        AgentRegistry registry = new AgentRegistry(List.of(diagnosis, coding), Set.of());
        try {
            RunSummary diagnosed = registry.dispatch(
                    diagnosis.id(), diagnosisRequest(), RunSummary.class);
            CodingTask coded = registry.dispatch(
                    coding.id(), codingRequest(), CodingTask.class);

            assertThat(diagnosed.reason()).isEqualTo(ExitReason.SUCCESS);
            assertThat(coded.status()).isEqualTo(CodingStatus.ACCEPTED);
        } finally {
            diagnosis.entryPoint().close();
        }
    }

    private static AgentManifest<RunRequest, RunSummary> diagnosisManifest() {
        return DiagnoseEngineBuilder.create()
                .llm(new StubLlmClient().enqueue(AiMessage.text("diagnosed")))
                .buildManifest();
    }

    private static AgentManifest<CodingRequest, CodingTask> codingManifest() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolCall("plan", "update_plan", "{\"problemStatement\":\"fix\"}"))
                .enqueue(toolCall("patch", "submit_patch", "{\"summary\":\"fixed\"}"))
                .enqueue(toolCall("review", "submit_review",
                        "{\"decision\":\"ACCEPT\",\"summary\":\"ok\"}"));
        return CodingEngineBuilder.create().llm(llm).buildManifest();
    }

    private static RunRequest diagnosisRequest() {
        return RunRequest.builder()
                .workingDir(".")
                .userMessage("Investigate")
                .sessionId("diagnosis-manifest")
                .build();
    }

    private static CodingRequest codingRequest() {
        return new CodingRequest(
                "coding-manifest", "Fix the issue", AgentRunContext.at(Path.of(".")));
    }

    private static AiMessage toolCall(String id, String name, String arguments) {
        return AiMessage.of("", List.of(new ToolUseRequest(
                new ToolUseId(id), name, arguments)));
    }
}
