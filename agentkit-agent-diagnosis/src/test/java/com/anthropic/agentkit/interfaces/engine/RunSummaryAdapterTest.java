package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentUsage;
import com.anthropic.agentkit.domain.agent.BudgetConsumption;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.message.AiMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RunSummaryAdapterTest {

    @Test
    void mapsKernelTerminalStateAndUsageToDiagnosisSummary() {
        OrchestrationResult result = new OrchestrationResult(
                "snapshot", run(StopReason.TERMINAL_TOOL, new AgentUsage(11, 7, 3)));

        RunSummary summary = RunSummaryAdapter.from(result);

        assertThat(summary.reason()).isEqualTo(ExitReason.SUCCESS);
        assertThat(summary.stateSnapshot()).isEqualTo("snapshot");
        assertThat(summary.usage()).isEqualTo(new RunSummary.Usage(11, 7, 3));
    }

    @Test
    void mapsControlAndFailureReasonsWithoutTextParsing() {
        assertThat(summaryReason(StopReason.CANCELLED)).isEqualTo(ExitReason.STOPPED);
        assertThat(summaryReason(StopReason.TIMED_OUT)).isEqualTo(ExitReason.TIMEOUT);
        assertThat(summaryReason(StopReason.PROVIDER_ERROR)).isEqualTo(ExitReason.ERROR);
        assertThat(summaryReason(StopReason.TOOL_PROTOCOL_ERROR)).isEqualTo(ExitReason.ERROR);
    }

    private static ExitReason summaryReason(StopReason reason) {
        return RunSummaryAdapter.from(new OrchestrationResult("", run(reason, AgentUsage.zero())))
                .reason();
    }

    private static AgentRunResult run(StopReason reason, AgentUsage usage) {
        return new AgentRunResult(
                RunId.of("adapter-run"), reason, AiMessage.text("done"),
                Optional.empty(), usage, BudgetConsumption.zero());
    }
}
