package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.skill.Skill;
import com.anthropic.cclc.domain.skill.SkillCatalog;
import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;

import java.util.Objects;

/**
 * Expands one cataloged skill into the conversation as a read-only tool result.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class SkillTool implements Tool {

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "skill":{"type":"string","description":"skills catalog 中列出的 skill 名称"}\
            },"required":["skill"]}""";

    private final SkillCatalog catalog;

    public SkillTool(SkillCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public String name() {
        return "Skill";
    }

    @Override
    public String description() {
        return "展开一个 Skill 获取完整操作指引。仅当 skills 目录中某条 description 与当前任务匹配时调用。";
    }

    @Override
    public String inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        String name = args.getString("skill");
        return catalog.find(name)
                .map(skill -> ToolResult.ok(renderSkill(skill)))
                .orElseGet(() -> ToolResult.error(unknownSkill(name)));
    }

    private static String renderSkill(Skill skill) {
        return "# Skill: " + skill.name() + "\n"
                + "# base: " + skill.baseDir() + "\n"
                + "# 引用文件以 base 为相对根，用 Read 工具读取。\n"
                + skill.body();
    }

    private String unknownSkill(String name) {
        return "unknown skill: " + name + ". available: " + catalog.names();
    }
}
