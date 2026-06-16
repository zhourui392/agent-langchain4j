package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputRendererTest {

    @Test
    void llmRequestStartShowsThinkingMarker() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);

        renderer.onLlmRequestStart();

        assertThat(io.output()).contains("thinking");
    }

    @Test
    void assistantTextStreamsWithoutAddingTrailingNewline() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);

        renderer.onLlmRequestStart();
        renderer.onAssistantTextDelta("hel");
        renderer.onAssistantTextDelta("lo");

        assertThat(io.output()).contains("hello");
    }

    @Test
    void turnCompleteAddsTrailingNewlineAfterAssistantText() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);

        renderer.onLlmRequestStart();
        renderer.onAssistantTextDelta("done");
        renderer.onTurnComplete(AiMessage.text("done"));

        assertThat(io.output()).endsWith("\n");
    }

    @Test
    void toolUseStartShowsToolNameAndArgs() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);

        renderer.onToolUseStart(new ToolUseRequest(
                new ToolUseId("u1"), "Bash", "{\"command\":\"ls\"}"));

        assertThat(io.output())
                .contains("Bash")
                .contains("{\"command\":\"ls\"}");
    }

    @Test
    void toolUseEndShowsSuccessMarkerWithDurationAndPreview() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);
        ToolUseRequest req = new ToolUseRequest(new ToolUseId("u1"), "Bash", "{}");

        renderer.onToolUseEnd(req, ToolResult.ok("hello world"), 42L);

        assertThat(io.output())
                .contains("42ms")
                .contains("hello world");
    }

    @Test
    void toolUseEndShowsErrorMarkerOnFailure() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);
        ToolUseRequest req = new ToolUseRequest(new ToolUseId("u1"), "Bash", "{}");

        renderer.onToolUseEnd(req, ToolResult.error("permission denied: Bash"), 3L);

        assertThat(io.output())
                .contains("3ms")
                .contains("permission denied: Bash");
    }

    @Test
    void toolUseEndTruncatesMultilineOutputToPreviewWithMoreCount() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);
        ToolUseRequest req = new ToolUseRequest(new ToolUseId("u1"), "Glob", "{}");
        String many = "a.txt\nb.txt\nc.txt\nd.txt\ne.txt\nf.txt\ng.txt";

        renderer.onToolUseEnd(req, ToolResult.ok(many), 10L);

        assertThat(io.output())
                .contains("a.txt")
                .contains("b.txt")
                .contains("more line");
        assertThat(io.output()).doesNotContain("g.txt");
    }

    @Test
    void toolUseEndTruncatesVeryLongSingleLine() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);
        ToolUseRequest req = new ToolUseRequest(new ToolUseId("u1"), "Read", "{}");
        String oneLongLine = "x".repeat(500);

        renderer.onToolUseEnd(req, ToolResult.ok(oneLongLine), 10L);

        String written = io.output();
        assertThat(written).contains("...");
        assertThat(written.length()).isLessThan(oneLongLine.length());
    }

    @Test
    void errorsGoToErrorChannel() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);

        renderer.onError(new RuntimeException("boom"));

        assertThat(io.errorOutput()).contains("error: boom");
        assertThat(io.output()).isEmpty();
    }

    @Test
    void colorDisabledOmitsAnsiEscapes() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);

        renderer.onLlmRequestStart();
        renderer.onAssistantTextDelta("hi");
        renderer.onToolUseStart(new ToolUseRequest(new ToolUseId("u"), "Bash", "{}"));
        renderer.onToolUseEnd(new ToolUseRequest(new ToolUseId("u"), "Bash", "{}"),
                ToolResult.ok("x"), 1L);
        renderer.onError(new RuntimeException("e"));

        assertThat(io.output()).doesNotContain("[");
        assertThat(io.errorOutput()).doesNotContain("[");
    }
}
