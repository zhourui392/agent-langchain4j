package com.anthropic.agentkit.domain.conversation;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantTurnTest {

    @Test
    void tracksOrderedBatchUntilEveryRequestIsSettled() {
        AssistantTurn turn = AssistantTurn.from(batch("first", "second"));

        assertThat(turn.state()).isEqualTo(AssistantTurnState.RECEIVED);
        turn.settle(result("first"));
        assertThat(turn.state()).isEqualTo(AssistantTurnState.SETTLING);
        assertThat(turn.settledCount()).isOne();
        turn.settle(result("second"));
        assertThat(turn.state()).isEqualTo(AssistantTurnState.SETTLED);
        assertThat(turn.isSettled()).isTrue();
    }

    @Test
    void validatesEntireBatchBeforeCreatingTurn() {
        AiMessage duplicate = batch("same", "same");

        assertThatThrownBy(() -> AssistantTurn.from(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same");
    }

    @Test
    void rejectsOutOfOrderSettlementWithoutAdvancing() {
        AssistantTurn turn = AssistantTurn.from(batch("first", "second"));

        assertThatThrownBy(() -> turn.settle(result("second")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order");
        assertThat(turn.state()).isEqualTo(AssistantTurnState.RECEIVED);
        assertThat(turn.settledCount()).isZero();
    }

    private static AiMessage batch(String... ids) {
        List<ToolUseRequest> requests = java.util.Arrays.stream(ids)
                .map(id -> new ToolUseRequest(new ToolUseId(id), "Read", "{}"))
                .toList();
        return AiMessage.of("", requests);
    }

    private static ToolResultMessage result(String id) {
        return ToolResultMessage.of(new ToolUseId(id), "done");
    }
}
