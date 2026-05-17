package com.anthropic.cclc.interfaces.cli;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import com.anthropic.cclc.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutputRendererTest {

    @Test
    void streamsPartialTokensWithoutTrailingNewline() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);

        renderer.onPartialText("hel");
        renderer.onPartialText("lo");

        assertThat(io.output()).isEqualTo("hello");
    }

    @Test
    void onCompleteAddsTrailingNewline() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);

        renderer.onPartialText("done");
        renderer.onComplete(AiMessage.text("done"));

        assertThat(io.output()).isEqualTo("done\n");
    }

    @Test
    void rendersToolCallsOnComplete() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);

        AiMessage withTool = AiMessage.of("calling",
                List.of(new ToolUseRequest(new ToolUseId("u1"), "Bash", "{\"command\":\"ls\"}")));
        renderer.onComplete(withTool);

        assertThat(io.output())
                .contains("Bash")
                .contains("{\"command\":\"ls\"}");
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

        renderer.onError(new RuntimeException("boom"));

        assertThat(io.errorOutput()).doesNotContain("[");
    }
}
