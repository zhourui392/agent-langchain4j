package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentUsage;
import com.anthropic.agentkit.domain.agent.BudgetConsumption;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisBlocker;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisBlockerType;
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

    @Test
    void preservesWaitingAndBudgetDiagnosisOutcomes() {
        assertThat(summary(StopReason.WAITING_FOR_INPUT).outcome())
                .isEqualTo(DiagnosisOutcome.WAITING_FOR_USER_INPUT);
        assertThat(summary(StopReason.BUDGET_EXHAUSTED).outcome())
                .isEqualTo(DiagnosisOutcome.BUDGET_LIMITED);
    }

    @Test
    void exposesCapabilityBlockerWithoutChangingLegacySuccessCode() {
        DiagnosisBlocker blocker = new DiagnosisBlocker(
                DiagnosisBlockerType.CAPABILITY_UNAVAILABLE, "LOG_QUERY_NOT_CONFIGURED",
                "LogQuery is not configured", "Configure it", false);
        OrchestrationResult result = new OrchestrationResult(
                "snapshot", run(StopReason.TERMINAL_TOOL, AgentUsage.zero()),
                DiagnosisOutcome.CAPABILITY_UNAVAILABLE, java.util.List.of(blocker));

        RunSummary summary = RunSummaryAdapter.from(result);

        assertThat(summary.outcome()).isEqualTo(DiagnosisOutcome.CAPABILITY_UNAVAILABLE);
        assertThat(summary.blockers()).singleElement().satisfies(view -> {
            assertThat(view.type()).isEqualTo(DiagnosisBlockerType.CAPABILITY_UNAVAILABLE);
            assertThat(view.code()).isEqualTo("LOG_QUERY_NOT_CONFIGURED");
        });
        assertThat(summary.legacyExitCode()).isZero();
    }

    @Test
    void failsClosedWhenTerminalStateOrErrorContainsASecret() {
        RunSummary summary = new RunSummary(
                ExitReason.ERROR, "{\"token\":\"state-marker\"}", RunSummary.Usage.zero(),
                "Provider failed with Bearer error-marker");

        assertThat(summary.stateSnapshot()).isEqualTo("***");
        assertThat(summary.errorDetail()).isEqualTo("***");
        assertThat(summary.toString()).doesNotContain("state-marker", "error-marker", "Bearer");
    }

    @Test
    void preservesStateThatContainsOnlyRedactedPlaceholders() {
        String snapshot = "{\"rawExcerpt\":\"ERROR apiKey=***\\nAuthorization: Bearer ***\"}";

        RunSummary summary = new RunSummary(
                ExitReason.SUCCESS, snapshot, RunSummary.Usage.zero(), "");

        assertThat(summary.stateSnapshot()).isEqualTo(snapshot);
    }

    private static ExitReason summaryReason(StopReason reason) {
        return summary(reason).reason();
    }

    private static RunSummary summary(StopReason reason) {
        return RunSummaryAdapter.from(new OrchestrationResult("", run(reason, AgentUsage.zero())));
    }

    private static AgentRunResult run(StopReason reason, AgentUsage usage) {
        return new AgentRunResult(
                RunId.of("adapter-run"), reason, AiMessage.text("done"),
                Optional.empty(), usage, BudgetConsumption.zero());
    }
}
