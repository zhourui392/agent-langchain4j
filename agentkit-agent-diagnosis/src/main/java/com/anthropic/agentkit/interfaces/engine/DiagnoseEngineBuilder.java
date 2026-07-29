package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.application.diagnosis.DiagnosisPlanner;
import com.anthropic.agentkit.application.diagnosis.DiagnosisReporter;
import com.anthropic.agentkit.application.diagnosis.PlanGuardMode;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentManifest;
import com.anthropic.agentkit.domain.agent.CapabilityDescriptor;
import com.anthropic.agentkit.domain.agent.ConfigKey;
import com.anthropic.agentkit.domain.agent.ToolCapabilitySet;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.skill.SkillCatalog;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnoseToolFactory;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisBackendConfig;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisToolBackends;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisToolBackendsFactory;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisToolPolicy;
import com.anthropic.agentkit.infrastructure.diagnosis.PromptPackLoader;
import com.anthropic.agentkit.infrastructure.diagnosis.StructuredDiagnosisPlanner;
import com.anthropic.agentkit.infrastructure.diagnosis.StructuredDiagnosisReporter;
import com.anthropic.agentkit.infrastructure.skill.DirectorySkillSource;
import com.anthropic.agentkit.infrastructure.skill.SkillFrontmatterParser;
import com.anthropic.agentkit.infrastructure.tools.SkillTool;
import com.anthropic.agentkit.infrastructure.tools.TruncatingTool;
import com.anthropic.agentkit.infrastructure.tools.governance.GovernedTool;
import com.anthropic.agentkit.infrastructure.tools.governance.ToolGovernance;
import com.anthropic.agentkit.infrastructure.tools.support.ToolResultTruncator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit assembly root for the in-process diagnosis agent.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class DiagnoseEngineBuilder {

    private static final int SKILL_CATALOG_WARN_THRESHOLD = 50;
    private static final Logger log = LoggerFactory.getLogger(DiagnoseEngineBuilder.class);

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
    private Set<ConfigKey> requiredConfigKeys = Set.of();

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
        registerSkillTool();
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

    public DiagnoseEngineBuilder requiredConfigKeys(Set<ConfigKey> keys) {
        this.requiredConfigKeys = Set.copyOf(Objects.requireNonNull(keys, "keys"));
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
        warnIfSkillCatalogIsLarge(root);
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

    public AgentManifest<RunRequest, RunSummary> buildManifest() {
        ToolCapabilitySet capabilities = ToolCapabilitySet.copyOf(
                new LinkedHashSet<>(tools.names()));
        return new AgentManifest<>(
                AgentId.of("diagnosis"),
                "Investigate a scoped operational problem and report evidence",
                build(), requiredConfigKeys,
                new CapabilityDescriptor(capabilities, Set.of()));
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

    private void warnIfSkillCatalogIsLarge(Path root) {
        if (skills.names().size() <= SKILL_CATALOG_WARN_THRESHOLD) {
            return;
        }
        log.warn("loaded {} skills from {}; catalog prompt may be large",
                skills.names().size(), root);
    }
}
