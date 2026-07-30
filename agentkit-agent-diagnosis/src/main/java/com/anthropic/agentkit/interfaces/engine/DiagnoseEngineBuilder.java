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
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalog;
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
import com.anthropic.agentkit.infrastructure.tools.support.BackendHealth;
import com.anthropic.agentkit.infrastructure.tools.support.BackendHealthIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.List;

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
    private ToolGovernance governance = DiagnoseToolFactory.safeDefaults();
    private ToolResultTruncator truncator = ToolResultTruncator.withDefaults();
    private DiagnosisPlanner planner;
    private DiagnosisReporter reporter;
    private PlanGuardMode guardMode = PlanGuardMode.OBSERVE;
    private DiagnosisToolPolicy toolPolicy = DiagnosisToolPolicy.denyByDefault();
    private String promptPack = "";
    private SkillCatalog skills;
    private Set<ConfigKey> requiredConfigKeys = Set.of();
    private DiagnosisMode mode = DiagnosisMode.CONVERSATIONAL;
    private ReadinessPolicy readinessPolicy = ReadinessPolicy.degradedStartup();
    private DiagnosisResourceCatalog resourceCatalog = DiagnosisResourceCatalog.empty();
    private Map<String, BackendHealth> backendHealth = Map.of();

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

    public DiagnoseEngineBuilder mode(DiagnosisMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        return this;
    }

    public DiagnoseEngineBuilder readinessPolicy(ReadinessPolicy readinessPolicy) {
        this.readinessPolicy = Objects.requireNonNull(readinessPolicy, "readinessPolicy");
        return this;
    }

    public DiagnoseEngineBuilder resourceCatalog(DiagnosisResourceCatalog resourceCatalog) {
        this.resourceCatalog = Objects.requireNonNull(resourceCatalog, "resourceCatalog");
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
        this.planner = new StructuredDiagnosisPlanner(llm, tools.names());
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
        DiagnosisReadiness readiness = readiness();
        if (readinessPolicy.failOnUnavailable()
                && readiness.status() == ReadinessStatus.UNAVAILABLE) {
            throw new IllegalStateException(
                    "OPERATIONAL diagnosis requires at least one evidence-producing tool");
        }
        return new DefaultDiagnoseEngine(llm, tools,
                new DefaultDiagnoseEngine.EngineOptions(
                        budget, planner, reporter, guardMode, promptPack,
                        renderSkillCatalog(), readiness, resourceCatalog));
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
        DiagnosisToolBackends effective = healthFiltered(backends);
        this.tools = new DiagnoseToolFactory(governance, truncator, toolPolicy).create(effective);
        registerSkillTool();
    }

    private DiagnosisToolBackends healthFiltered(DiagnosisToolBackends configured) {
        if (!(configured.logQuery() instanceof BackendHealthIndicator indicator)) {
            backendHealth = Map.of();
            return configured;
        }
        BackendHealth health = indicator.health();
        backendHealth = Map.of("LogQuery", health);
        if (health.status() != ReadinessStatus.UNAVAILABLE) {
            return configured;
        }
        return new DiagnosisToolBackends(null, configured.es(), configured.mysql(),
                configured.redis(), configured.http(), configured.dubbo());
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

    private DiagnosisReadiness readiness() {
        if (mode == DiagnosisMode.CONVERSATIONAL) {
            return DiagnosisReadiness.conversational();
        }
        Map<String, DiagnosisCapability> capabilities = new LinkedHashMap<>();
        tools.names().stream()
                .filter(DiagnoseEngineBuilder::producesEvidence)
                .forEach(name -> capabilities.put(name, capability(name,
                        backendHealth.get(name))));
        backendHealth.forEach((name, health) -> capabilities.putIfAbsent(
                name, capability(name, health)));
        if (capabilities.isEmpty()) {
            return new DiagnosisReadiness(
                    ReadinessStatus.UNAVAILABLE, mode, List.of(),
                    "EVIDENCE_TOOL_NOT_CONFIGURED");
        }
        ReadinessStatus status = aggregateReadiness(capabilities.values().stream()
                .map(DiagnosisCapability::readiness).toList());
        String reason = status == ReadinessStatus.READY ? "" : capabilities.values().stream()
                .filter(capability -> capability.readiness() != ReadinessStatus.READY)
                .map(DiagnosisCapability::reasonCode).filter(value -> !value.isBlank())
                .findFirst().orElse("BACKEND_NOT_READY");
        return new DiagnosisReadiness(status, mode,
                List.copyOf(capabilities.values()), reason);
    }

    private DiagnosisCapability capability(String name, BackendHealth health) {
        ReadinessStatus status = health == null ? ReadinessStatus.READY : health.status();
        String reason = health == null || status == ReadinessStatus.READY
                ? "" : health.reasonCode();
        String dataSourceId = "";
        String environment = "";
        if ("LogQuery".equals(name) && backends != null && backends.logQuery() != null) {
            dataSourceId = backends.logQuery().dataSourceId();
            environment = backends.logQuery().environment();
        }
        return new DiagnosisCapability(
                name, dataSourceId, environment, status, Set.of("query"), reason);
    }

    private ReadinessStatus aggregateReadiness(List<ReadinessStatus> statuses) {
        if (statuses.stream().anyMatch(status -> status == ReadinessStatus.READY)) {
            return ReadinessStatus.READY;
        }
        if (statuses.stream().anyMatch(status -> status == ReadinessStatus.DEGRADED)) {
            return ReadinessStatus.DEGRADED;
        }
        return ReadinessStatus.UNAVAILABLE;
    }

    private static boolean producesEvidence(String toolName) {
        return Set.of("LogQuery", "EsRead", "MysqlRead", "RedisRead", "HttpGet", "DubboInvoke")
                .contains(toolName);
    }

    private void warnIfSkillCatalogIsLarge(Path root) {
        if (skills.names().size() <= SKILL_CATALOG_WARN_THRESHOLD) {
            return;
        }
        log.warn("loaded {} skills from {}; catalog prompt may be large",
                skills.names().size(), root);
    }
}
