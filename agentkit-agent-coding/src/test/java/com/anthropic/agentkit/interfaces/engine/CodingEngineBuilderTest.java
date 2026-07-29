package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.agent.AgentManifest;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.coding.CodingStatus;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.port.ToolSpec;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodingEngineBuilderTest {

    @Test
    void requiresLlmClient() {
        assertThatThrownBy(() -> CodingEngineBuilder.create().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm");
    }

    @Test
    void exposesStableEntryPointWithoutHostAssemblingRoles() {
        StubLlmClient llm = scriptedCodingRun();
        CodingEngine engine = CodingEngineBuilder.create().llm(llm).build();

        CodingTask result = engine.invoke(new CodingRequest(
                "task-1", "Add login", AgentRunContext.at(Path.of("."))));

        assertThat(result.status()).isEqualTo(CodingStatus.ACCEPTED);
        assertThat(result.plan().problemStatement()).isEqualTo("Add login");
        assertThat(result.patch().summary()).isEqualTo("implemented");
    }

    @Test
    void manifestCapabilitiesMatchRoleToolBoundaries() {
        Tool write = FakeTool.returning("Write", "ok");
        StubLlmClient llm = scriptedCodingRun();
        AgentManifest<CodingRequest, CodingTask> manifest = CodingEngineBuilder.create()
                .llm(llm)
                .codingTools(List.of(write))
                .buildManifest();

        manifest.entryPoint().invoke(new CodingRequest(
                "task-1", "Add login", AgentRunContext.at(Path.of("."))));

        assertThat(manifest.id().value()).isEqualTo("coding");
        assertThat(manifest.capabilities().allowedTools().names()).containsExactly("Write");
        assertThat(manifest.capabilities().terminalTools())
                .containsExactly("update_plan", "submit_patch", "submit_review");
        assertThat(manifest.entryPoint().requestType()).isEqualTo(CodingRequest.class);
        assertThat(manifest.entryPoint().resultType()).isEqualTo(CodingTask.class);
        assertThat(toolNames(llm, 0)).containsExactly("update_plan");
        assertThat(toolNames(llm, 1)).containsExactly("Write", "submit_patch");
        assertThat(toolNames(llm, 2)).containsExactly("submit_review");
    }

    private static StubLlmClient scriptedCodingRun() {
        return new StubLlmClient()
                .enqueue(toolCall("plan", "update_plan",
                        "{\"problemStatement\":\"Add login\",\"tasks\":[]}"))
                .enqueue(toolCall("patch", "submit_patch",
                        "{\"summary\":\"implemented\",\"changes\":[]}"))
                .enqueue(toolCall("review", "submit_review",
                        "{\"decision\":\"ACCEPT\",\"summary\":\"ok\"}"));
    }

    private static AiMessage toolCall(String id, String name, String arguments) {
        return AiMessage.of("", List.of(new ToolUseRequest(
                new ToolUseId(id), name, arguments)));
    }

    private static List<String> toolNames(StubLlmClient llm, int requestIndex) {
        return llm.capturedRequests().get(requestIndex).tools().stream()
                .map(ToolSpec::name)
                .toList();
    }
}
