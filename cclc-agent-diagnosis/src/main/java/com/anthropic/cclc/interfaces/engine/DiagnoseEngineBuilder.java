package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.application.diagnosis.DiagnosisPlanner;
import com.anthropic.cclc.domain.agent.AgentBudget;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnoseToolFactory;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnosisToolBackends;
import com.anthropic.cclc.infrastructure.diagnosis.PromptPackLoader;
import com.anthropic.cclc.infrastructure.tools.governance.ToolGovernance;
import com.anthropic.cclc.infrastructure.tools.support.ToolResultTruncator;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Explicit assembly root for the in-process diagnosis agent.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class DiagnoseEngineBuilder {

    private LlmClient llm;
    private ToolRegistry tools = new ToolRegistry();
    private AgentBudget budget = AgentBudget.unlimited();
    private DiagnosisPlanner planner;
    private String promptPack = "";

    private DiagnoseEngineBuilder() {
    }

    public static DiagnoseEngineBuilder create() {
        return new DiagnoseEngineBuilder();
    }

    public DiagnoseEngineBuilder llm(LlmClient llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
        return this;
    }

    public DiagnoseEngineBuilder tools(ToolRegistry tools) {
        this.tools = Objects.requireNonNull(tools, "tools");
        return this;
    }

    public DiagnoseEngineBuilder toolBackends(DiagnosisToolBackends backends) {
        return toolBackends(backends, ToolGovernance.defaults());
    }

    public DiagnoseEngineBuilder toolBackends(DiagnosisToolBackends backends,
                                              ToolGovernance governance) {
        return toolBackends(backends, governance, ToolResultTruncator.withDefaults());
    }

    public DiagnoseEngineBuilder toolBackends(DiagnosisToolBackends backends,
                                              ToolGovernance governance,
                                              ToolResultTruncator truncator) {
        this.tools = new DiagnoseToolFactory(governance, truncator)
                .create(Objects.requireNonNull(backends, "backends"));
        return this;
    }

    public DiagnoseEngineBuilder budget(AgentBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
        return this;
    }

    public DiagnoseEngineBuilder planner(DiagnosisPlanner planner) {
        this.planner = Objects.requireNonNull(planner, "planner");
        return this;
    }

    public DiagnoseEngineBuilder promptPacks(Path directory) {
        this.promptPack = new PromptPackLoader().load(directory);
        return this;
    }

    public DiagnoseEngine build() {
        if (llm == null) {
            throw new IllegalStateException("llm must be configured");
        }
        return new DefaultDiagnoseEngine(llm, tools, budget, planner, promptPack);
    }
}
