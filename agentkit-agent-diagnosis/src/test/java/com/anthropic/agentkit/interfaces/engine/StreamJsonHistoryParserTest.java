package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.infrastructure.streamjson.ClaudeStreamJsonWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class StreamJsonHistoryParserTest {

    private final StreamJsonHistoryParser parser = new StreamJsonHistoryParser();
    private final ClaudeStreamJsonWriter writer = new ClaudeStreamJsonWriter();

    @Test
    void parsesAssistantTextAndToolUseTurns() {
        String line = writer.assistantMessage("looking",
                List.of(new ClaudeStreamJsonWriter.AssistantToolUse(
                        "tu-1", "LogQuery", "{\"q\":\"err\"}")));

        List<TurnMessage> turns = parser.parse(Stream.of(line, writer.toolResult("tu-1", "found 3")));

        assertThat(turns).hasSize(2);
        AssistantTurn assistant = (AssistantTurn) turns.get(0);
        assertThat(assistant.text()).isEqualTo("looking");
        assertThat(assistant.toolCalls()).containsExactly(
                new ToolCall("tu-1", "LogQuery", "{\"q\":\"err\"}"));
    }

    @Test
    void parsesToolResultUserTurns() {
        List<TurnMessage> turns = parser.parse(Stream.of(
                writer.assistantMessage("", List.of(new ClaudeStreamJsonWriter.AssistantToolUse(
                        "tu-1", "LogQuery", "{}"))),
                writer.toolResult("tu-1", "found 3")));

        assertThat(turns).containsExactly(
                new AssistantTurn("", List.of(new ToolCall("tu-1", "LogQuery", "{}"))),
                new ToolResultTurn("tu-1", "found 3"));
    }

    @Test
    void skipsStreamEventAndResultLines() {
        List<TurnMessage> turns = parser.parse(Stream.of(
                writer.textDelta("hello"),
                writer.result("done", "s-1")));

        assertThat(turns).isEmpty();
    }

    @Test
    void skipsMalformedLineAndKeepsRest() {
        List<TurnMessage> turns = parser.parse(Stream.of(
                "{not-json",
                writer.assistantMessage("ok", List.of())));

        assertThat(turns).containsExactly(AssistantTurn.text("ok"));
    }

    @Test
    void dropsOrphanToolUseToPreservePairing() {
        List<TurnMessage> turns = parser.parse(Stream.of(
                writer.assistantMessage("will call", List.of(new ClaudeStreamJsonWriter.AssistantToolUse(
                        "tu-1", "LogQuery", "{}"))),
                writer.assistantMessage("plain", List.of()),
                writer.toolResult("missing", "orphan")));

        assertThat(turns).containsExactly(
                AssistantTurn.text("will call"),
                AssistantTurn.text("plain"));
    }

    @Test
    void roundTripsThroughConversationRebuilder() {
        List<String> lines = List.of(
                writer.assistantMessage("looking", List.of(new ClaudeStreamJsonWriter.AssistantToolUse(
                        "tu-1", "LogQuery", "{\"q\":\"err\"}"))),
                writer.toolResult("tu-1", "found 3"),
                writer.assistantMessage("done", List.of()));

        List<TurnMessage> turns = parser.parse(lines.stream());

        assertThatCode(() -> new ConversationRebuilder().from(SessionId.of("s-1"), turns, "next"))
                .doesNotThrowAnyException();
    }
}
