package com.anthropic.agentkit.application.interception;

import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentRunState;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.SessionId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentInterceptionContractTest {

    @Test
    void denialDecisionsRequireAReason() {
        assertThatThrownBy(() -> LlmCallDecision.deny(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolDispatchDecision.deny(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompactionDecision.deny(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunStopDecision.deny(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lifecycleEventRejectsActiveStateWithStopReason() {
        assertThatThrownBy(() -> new SubAgentLifecycleEvent(
                AgentId.of("worker"), RunId.of("parent"), RunId.of("child"),
                SessionId.of("child-session"), AgentRunState.RUNNING,
                Optional.of(StopReason.MODEL_COMPLETED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active");
    }

    @Test
    void completedLifecycleEventRequiresStopReason() {
        assertThatThrownBy(() -> SubAgentLifecycleEvent.stopped(
                AgentId.of("worker"), RunId.of("parent"), RunId.of("child"),
                SessionId.of("child-session"), AgentRunState.COMPLETED,
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a stop reason");
    }
}
