package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.ToolResultTruncator;
import com.anthropic.cclc.testsupport.FakeTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TruncatingToolTest {

    private final ExecutionContext ctx = ExecutionContext.at(Paths.get(System.getProperty("user.dir")));
    private final ToolResultTruncator truncator = ToolResultTruncator.withDefaults();

    @Test
    void truncatesLargeDelegateOutput() {
        String big = IntStream.range(0, 500).mapToObj(i -> "row " + i).collect(Collectors.joining("\n"));
        TruncatingTool tool = new TruncatingTool(FakeTool.readOnlyReturning("EsRead", big), truncator);

        ToolResult result = tool.execute(ToolArguments.empty(), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("omitted");
        assertThat(result.content().length()).isLessThan(big.length());
    }

    @Test
    void passesThroughSmallOutput() {
        TruncatingTool tool = new TruncatingTool(FakeTool.readOnlyReturning("Get", "small"), truncator);

        assertThat(tool.execute(ToolArguments.empty(), ctx).content()).isEqualTo("small");
    }

    @Test
    void preservesErrorFlag() {
        TruncatingTool tool = new TruncatingTool(
                FakeTool.withBehavior("Boom", args -> ToolResult.error("boom")), truncator);

        ToolResult result = tool.execute(ToolArguments.empty(), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).isEqualTo("boom");
    }

    @Test
    void delegatesMetadata() {
        TruncatingTool tool = new TruncatingTool(FakeTool.readOnlyReturning("EsRead", "x"), truncator);

        assertThat(tool.name()).isEqualTo("EsRead");
        assertThat(tool.isReadOnly()).isTrue();
    }
}
