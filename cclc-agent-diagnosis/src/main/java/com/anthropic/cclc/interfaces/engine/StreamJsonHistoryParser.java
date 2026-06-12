package com.anthropic.cclc.interfaces.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Rebuilds engine turn history from persisted Claude stream-json lines.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class StreamJsonHistoryParser {

    private static final Logger log = LoggerFactory.getLogger(StreamJsonHistoryParser.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parses persisted stream-json lines into engine turn messages.
     *
     * @param lines persisted stream-json lines
     * @return history safe to feed into {@link ConversationRebuilder}
     */
    public List<TurnMessage> parse(Stream<String> lines) {
        Objects.requireNonNull(lines, "lines");
        List<TurnMessage> turns = new ArrayList<>();
        lines.map(this::parseLine).forEach(parsed -> parsed.appendTo(turns));
        return dropUnpairedToolTurns(turns);
    }

    private ParsedLine parseLine(String line) {
        try {
            return parseNode(mapper.readTree(line));
        } catch (JsonProcessingException ex) {
            log.warn("skip malformed stream-json history line", ex);
            return ParsedLine.skip();
        }
    }

    private ParsedLine parseNode(JsonNode node) {
        return switch (node.path("type").asText()) {
            case "assistant" -> ParsedLine.turn(assistantTurn(node.path("message").path("content")));
            case "user" -> toolResultTurn(node.path("message").path("content"));
            default -> ParsedLine.skip();
        };
    }

    private AssistantTurn assistantTurn(JsonNode content) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode block : content) {
            appendAssistantBlock(block, text, toolCalls);
        }
        return new AssistantTurn(text.toString(), toolCalls);
    }

    private void appendAssistantBlock(JsonNode block, StringBuilder text, List<ToolCall> toolCalls) {
        switch (block.path("type").asText()) {
            case "text" -> text.append(block.path("text").asText());
            case "tool_use" -> toolCalls.add(toolCall(block));
            default -> {
            }
        }
    }

    private ToolCall toolCall(JsonNode block) {
        return new ToolCall(
                block.path("id").asText(),
                block.path("name").asText(),
                block.path("input").toString());
    }

    private ParsedLine toolResultTurn(JsonNode content) {
        for (JsonNode block : content) {
            if (block.path("type").asText().equals("tool_result")) {
                return ParsedLine.turn(new ToolResultTurn(
                        block.path("tool_use_id").asText(),
                        block.path("content").asText()));
            }
        }
        return ParsedLine.skip();
    }

    private List<TurnMessage> dropUnpairedToolTurns(List<TurnMessage> turns) {
        Set<String> resultIds = resultIds(turns);
        Set<String> validToolIds = validToolIds(turns, resultIds);
        List<TurnMessage> out = new ArrayList<>();
        for (TurnMessage turn : turns) {
            appendIfPaired(turn, validToolIds, out);
        }
        return out;
    }

    private Set<String> resultIds(List<TurnMessage> turns) {
        Set<String> ids = new HashSet<>();
        for (TurnMessage turn : turns) {
            if (turn instanceof ToolResultTurn result) {
                ids.add(result.toolUseId());
            }
        }
        return ids;
    }

    private Set<String> validToolIds(List<TurnMessage> turns, Set<String> resultIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (TurnMessage turn : turns) {
            if (turn instanceof AssistantTurn assistant) {
                assistant.toolCalls().stream()
                        .map(ToolCall::id)
                        .filter(resultIds::contains)
                        .forEach(ids::add);
            }
        }
        return ids;
    }

    private void appendIfPaired(TurnMessage turn, Set<String> validToolIds, List<TurnMessage> out) {
        switch (turn) {
            case AssistantTurn assistant -> appendAssistantIfPaired(assistant, validToolIds, out);
            case ToolResultTurn result -> appendToolResultIfPaired(result, validToolIds, out);
            case UserTurn user -> out.add(user);
        }
    }

    private void appendAssistantIfPaired(AssistantTurn assistant, Set<String> validToolIds,
                                         List<TurnMessage> out) {
        List<ToolCall> calls = assistant.toolCalls().stream()
                .filter(call -> validToolIds.contains(call.id()))
                .toList();
        if (!assistant.text().isEmpty() || !calls.isEmpty()) {
            out.add(new AssistantTurn(assistant.text(), calls));
        }
    }

    private void appendToolResultIfPaired(ToolResultTurn result, Set<String> validToolIds,
                                          List<TurnMessage> out) {
        if (validToolIds.contains(result.toolUseId())) {
            out.add(result);
        }
    }

    private sealed interface ParsedLine permits ParsedLine.Skip, ParsedLine.Turn {

        void appendTo(List<TurnMessage> turns);

        static ParsedLine skip() {
            return new Skip();
        }

        static ParsedLine turn(TurnMessage turn) {
            return new Turn(turn);
        }

        record Skip() implements ParsedLine {
            @Override
            public void appendTo(List<TurnMessage> turns) {
            }
        }

        record Turn(TurnMessage turn) implements ParsedLine {
            @Override
            public void appendTo(List<TurnMessage> turns) {
                turns.add(turn);
            }
        }
    }
}
