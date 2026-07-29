package com.anthropic.agentkit.infrastructure.coding;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.coding.CodingPlan;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.coding.FileChange;
import com.anthropic.agentkit.domain.coding.FileChangeType;
import com.anthropic.agentkit.domain.coding.Patch;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.port.ToolSpec;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredCodingPatcherTest {

    @Test
    void producesPatchFromSubmitPatchToolUse() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("patch-1"), "submit_patch", patchJson()))))
                .enqueue(AiMessage.text("patched"));
        StructuredCodingPatcher patcher = new StructuredCodingPatcher(llm, List.of());

        Patch patch = patcher.producePatch(
                CodingTask.open("task-1", "Add login page"), plan(), context());

        assertThat(patch.summary()).isEqualTo("implement login controller");
        assertThat(patch.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("src/Login.java");
            assertThat(change.changeType()).isEqualTo(FileChangeType.CREATE);
            assertThat(change.diff()).isEqualTo("+class Login {}");
        });
    }

    @Test
    void defaultsMissingChangeTypeToEdit() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("patch-1"), "submit_patch",
                        "{\"summary\":\"s\",\"changes\":[{\"path\":\"a.java\",\"diff\":\"d\"}]}"))))
                .enqueue(AiMessage.text("done"));
        StructuredCodingPatcher patcher = new StructuredCodingPatcher(llm, List.of());

        Patch patch = patcher.producePatch(CodingTask.open("task-1", "s"), plan(), context());

        assertThat(patch.changes()).singleElement()
                .extracting(FileChange::changeType)
                .isEqualTo(FileChangeType.EDIT);
    }

    @Test
    void toleratesAbsentChangesAsEmptyList() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("patch-1"), "submit_patch",
                        "{\"summary\":\"doc-only\"}"))))
                .enqueue(AiMessage.text("done"));
        StructuredCodingPatcher patcher = new StructuredCodingPatcher(llm, List.of());

        Patch patch = patcher.producePatch(
                CodingTask.open("task-1", "doc-only"), plan(), context());

        assertThat(patch.changes()).isEmpty();
    }

    @Test
    void exposesInjectedCodingToolsToModel() {
        Tool writeTool = FakeTool.returning("write_file", "ok");
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("patch-1"), "submit_patch",
                        "{\"summary\":\"s\"}"))))
                .enqueue(AiMessage.text("done"));
        StructuredCodingPatcher patcher = new StructuredCodingPatcher(llm, List.of(writeTool));

        patcher.producePatch(CodingTask.open("task-1", "s"), plan(), context());

        assertThat(llm.capturedRequests().get(0).tools())
                .extracting(ToolSpec::name)
                .contains("write_file", "submit_patch");
    }

    private static CodingPlan plan() {
        return new CodingPlan("Add login page", List.of());
    }

    private static AgentRunContext context() {
        return AgentRunContext.at(Path.of("."));
    }

    private static String patchJson() {
        return """
                {
                  "summary": "implement login controller",
                  "changes": [
                    {
                      "path": "src/Login.java",
                      "changeType": "CREATE",
                      "diff": "+class Login {}"
                    }
                  ]
                }
                """;
    }
}
