package com.anthropic.agentkit.infrastructure.agent;

import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.application.interception.AgentInterceptor;
import com.anthropic.agentkit.application.interception.AgentInterceptors;
import com.anthropic.agentkit.application.interception.SubAgentLifecycleEvent;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentRunState;
import com.anthropic.agentkit.domain.agent.AgentSpec;
import com.anthropic.agentkit.domain.agent.ModelTier;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.agent.SubAgentLimits;
import com.anthropic.agentkit.domain.agent.ToolCapabilitySet;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.port.LlmClientSelector;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSubAgentRuntimeInterceptorTest {

    @Test
    void subAgentLifecycleCarriesParentAndChildRunIds() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        List<SubAgentLifecycleEvent> events = new CopyOnWriteArrayList<>();
        AgentInterceptor interceptor = new AgentInterceptor() {
            @Override
            public void onSubAgentSpawned(SubAgentLifecycleEvent event) {
                events.add(event);
            }

            @Override
            public void onSubAgentStopped(SubAgentLifecycleEvent event) {
                events.add(event);
            }
        };
        AgentRunContext parent = AgentRunContext.create(
                com.anthropic.agentkit.domain.conversation.SessionId.fresh(),
                Path.of("."), new CancellationToken(), AgentBudget.unlimited());
        DefaultSubAgentRuntime runtime = runtime(llm, interceptor);

        AgentRunResult result = runtime.spawn(spec(), "work", parent.executionContext())
                .result().toCompletableFuture().join();

        assertThat(events).hasSize(2);
        assertCorrelation(events, parent, result);
    }

    private static void assertCorrelation(
            List<SubAgentLifecycleEvent> events,
            AgentRunContext parent, AgentRunResult result) {
        SubAgentLifecycleEvent spawned = events.getFirst();
        SubAgentLifecycleEvent stopped = events.getLast();
        assertThat(spawned.parentRunId()).isEqualTo(parent.runId());
        assertThat(spawned.childRunId()).isEqualTo(result.runId());
        assertThat(stopped.childRunId()).isEqualTo(result.runId());
        assertThat(spawned.childSessionId()).isEqualTo(stopped.childSessionId());
        assertThat(spawned.state()).isEqualTo(AgentRunState.RUNNING);
        assertThat(stopped.state()).isEqualTo(AgentRunState.COMPLETED);
        assertThat(stopped.stopReason()).contains(StopReason.MODEL_COMPLETED);
    }

    private static DefaultSubAgentRuntime runtime(
            StubLlmClient llm, AgentInterceptor interceptor) {
        return new DefaultSubAgentRuntime(
                LlmClientSelector.fixed(llm), new ToolRegistry(),
                PermissionService.bypassing(), new SubAgentLimits(2, 1),
                AgentInterceptors.ordered(interceptor));
    }

    private static AgentSpec spec() {
        return new AgentSpec(
                AgentId.of("worker"), "Complete the task.", ToolCapabilitySet.none(),
                ModelTier.DEFAULT, AgentBudget.unlimited(), AgentRunLimits.defaults(),
                Optional.empty());
    }
}
