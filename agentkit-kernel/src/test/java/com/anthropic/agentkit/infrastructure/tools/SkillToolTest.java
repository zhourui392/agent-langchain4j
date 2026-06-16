package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.skill.Skill;
import com.anthropic.agentkit.domain.skill.SkillCatalog;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillToolTest {

    @Test
    void exposesReadOnlySkillToolSchema(@TempDir Path dir) {
        SkillTool tool = new SkillTool(SkillCatalog.of(List.of(skill(dir))));

        assertThat(tool.name()).isEqualTo("Skill");
        assertThat(tool.description()).contains("Skill");
        assertThat(tool.inputSchema()).contains("\"skill\"", "\"required\"");
        assertThat(tool.isReadOnly()).isTrue();
    }

    @Test
    void returnsSkillBodyWithBaseDirectory(@TempDir Path dir) {
        SkillTool tool = new SkillTool(SkillCatalog.of(List.of(skill(dir))));

        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("skill", "es-slow-query")),
                ExecutionContext.of(dir, new CancellationToken()));

        assertThat(result.success()).isTrue();
        assertThat(result.content())
                .contains("# Skill: es-slow-query")
                .contains("# base: " + dir.resolve("es-slow-query").normalize())
                .contains("# ES Slow Query");
    }

    @Test
    void unknownSkillReturnsErrorWithAvailableNames(@TempDir Path dir) {
        SkillTool tool = new SkillTool(SkillCatalog.of(List.of(skill(dir))));

        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("skill", "missing")),
                ExecutionContext.of(dir, new CancellationToken()));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("unknown skill: missing", "es-slow-query");
    }

    private static Skill skill(Path dir) {
        return new Skill("es-slow-query", "Diagnose slow ES queries.",
                "# ES Slow Query\nFollow the SOP.", dir.resolve("es-slow-query").normalize());
    }
}
