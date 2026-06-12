package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.application.diagnosis.DiagnosisPlanner;
import com.anthropic.cclc.application.diagnosis.DiagnosisReporter;
import com.anthropic.cclc.application.diagnosis.PlanGuardMode;
import com.anthropic.cclc.domain.agent.AgentBudget;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.skill.SkillCatalog;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnoseToolFactory;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnosisBackendConfig;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnosisToolBackends;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnosisToolBackendsFactory;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnosisToolPolicy;
import com.anthropic.cclc.infrastructure.diagnosis.PromptPackLoader;
import com.anthropic.cclc.infrastructure.diagnosis.StructuredDiagnosisPlanner;
import com.anthropic.cclc.infrastructure.diagnosis.StructuredDiagnosisReporter;
import com.anthropic.cclc.infrastructure.skill.DirectorySkillSource;
import com.anthropic.cclc.infrastructure.skill.SkillFrontmatterParser;
import com.anthropic.cclc.infrastructure.tools.SkillTool;
import com.anthropic.cclc.infrastructure.tools.TruncatingTool;
import com.anthropic.cclc.infrastructure.tools.governance.GovernedTool;
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
    private DiagnosisToolBackends backends;
    private ToolGovernance governance = ToolGovernance.defaults();
    private ToolResultTruncator truncator = ToolResultTruncator.withDefaults();
    private DiagnosisPlanner planner;
    private DiagnosisReporter reporter;
    private PlanGuardMode guardMode = PlanGuardMode.OBSERVE;
    private DiagnosisToolPolicy toolPolicy = DiagnosisToolPolicy.allowAll();
    private String promptPack = "";
    private SkillCatalog skills;

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
        this.backends = Objects.requireNonNull(backends, "backends");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.truncator = Objects.requireNonNull(truncator, "truncator");
        assembleBackendTools();
        return this;
    }

    public DiagnoseEngineBuilder backendConfig(DiagnosisBackendConfig config) {
        return toolBackends(DiagnosisToolBackendsFactory.fromConfig(config));
    }

    public DiagnoseEngineBuilder budget(AgentBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
        return this;
    }

    public DiagnoseEngineBuilder planner(DiagnosisPlanner planner) {
        this.planner = Objects.requireNonNull(planner, "planner");
        return this;
    }

    public DiagnoseEngineBuilder reporter(DiagnosisReporter reporter) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        return this;
    }

    public DiagnoseEngineBuilder structuredDiagnosis() {
        if (llm == null) {
            throw new IllegalStateException("llm must be configured before structuredDiagnosis");
        }
        this.planner = new StructuredDiagnosisPlanner(llm);
        this.reporter = new StructuredDiagnosisReporter(llm);
        return this;
    }

    public DiagnoseEngineBuilder planGuardMode(PlanGuardMode guardMode) {
        this.guardMode = Objects.requireNonNull(guardMode, "guardMode");
        return this;
    }

    public DiagnoseEngineBuilder toolPolicy(DiagnosisToolPolicy toolPolicy) {
        this.toolPolicy = Objects.requireNonNull(toolPolicy, "toolPolicy");
        if (backends != null) {
            assembleBackendTools();
        }
        return this;
    }

    public DiagnoseEngineBuilder promptPacks(Path directory) {
        this.promptPack = new PromptPackLoader().load(directory);
        return this;
    }

    public DiagnoseEngineBuilder skills(Path root) {
        this.skills = SkillCatalog.of(new DirectorySkillSource(root, new SkillFrontmatterParser()).load());
        registerSkillTool();
        return this;
    }

    public DiagnoseEngine build() {
        if (llm == null) {
            throw new IllegalStateException("llm must be configured");
        }
        return new DefaultDiagnoseEngine(llm, tools,
                new DefaultDiagnoseEngine.EngineOptions(
                        budget, planner, reporter, guardMode, promptPack, renderSkillCatalog()));
    }

    private void assembleBackendTools() {
        this.tools = new DiagnoseToolFactory(governance, truncator, toolPolicy).create(backends);
        registerSkillTool();
    }

    private void registerSkillTool() {
        if (skills == null || skills.isEmpty() || tools.contains("Skill")) {
            return;
        }
        tools.register(new TruncatingTool(new GovernedTool(new SkillTool(skills), governance), truncator));
    }

    private String renderSkillCatalog() {
        return skills == null ? "" : skills.renderCatalog();
    }
}
