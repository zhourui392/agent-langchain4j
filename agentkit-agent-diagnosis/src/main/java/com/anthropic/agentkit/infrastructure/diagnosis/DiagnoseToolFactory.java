package com.anthropic.agentkit.infrastructure.diagnosis;

import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.infrastructure.tools.DubboInvokeTool;
import com.anthropic.agentkit.infrastructure.tools.EsReadTool;
import com.anthropic.agentkit.infrastructure.tools.HttpGetTool;
import com.anthropic.agentkit.infrastructure.tools.LogQueryTool;
import com.anthropic.agentkit.infrastructure.tools.MysqlReadTool;
import com.anthropic.agentkit.infrastructure.tools.RedisReadTool;
import com.anthropic.agentkit.infrastructure.tools.TruncatingTool;
import com.anthropic.agentkit.infrastructure.tools.governance.GovernedTool;
import com.anthropic.agentkit.infrastructure.tools.governance.ToolGovernance;
import com.anthropic.agentkit.infrastructure.tools.governance.ToolAuditSink;
import com.anthropic.agentkit.infrastructure.tools.support.ToolResultTruncator;

import java.util.Objects;
import java.time.Duration;

/**
 * Assembles production diagnosis tools with kernel governance wrappers.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class DiagnoseToolFactory {

    private final ToolGovernance governance;
    private final ToolResultTruncator truncator;
    private final DiagnosisToolPolicy policy;

    public DiagnoseToolFactory() {
        this(safeDefaults(), ToolResultTruncator.withDefaults());
    }

    public DiagnoseToolFactory(ToolGovernance governance, ToolResultTruncator truncator) {
        this(governance, truncator, DiagnosisToolPolicy.denyByDefault());
    }

    public DiagnoseToolFactory(ToolGovernance governance, ToolResultTruncator truncator,
                               DiagnosisToolPolicy policy) {
        this.governance = Objects.requireNonNull(governance, "governance");
        this.truncator = Objects.requireNonNull(truncator, "truncator");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public ToolRegistry create(DiagnosisToolBackends backends) {
        Objects.requireNonNull(backends, "backends");
        ToolRegistry registry = new ToolRegistry();
        registerLogQuery(registry, backends);
        registerEs(registry, backends);
        registerMysql(registry, backends);
        registerRedis(registry, backends);
        registerHttp(registry, backends);
        registerDubbo(registry, backends);
        return registry;
    }

    private void registerLogQuery(ToolRegistry registry, DiagnosisToolBackends backends) {
        if (backends.logQuery() != null) {
            registry.register(govern(new LogQueryTool(backends.logQuery())));
        }
    }

    private void registerEs(ToolRegistry registry, DiagnosisToolBackends backends) {
        if (backends.es() != null) {
            registry.register(govern(new EsReadTool(
                    backends.es(), policy.allowedEsIndices())));
        }
    }

    private void registerMysql(ToolRegistry registry, DiagnosisToolBackends backends) {
        if (backends.mysql() != null) {
            registry.register(govern(new MysqlReadTool(
                    backends.mysql(), policy.allowedMysqlSchemas())));
        }
    }

    private void registerRedis(ToolRegistry registry, DiagnosisToolBackends backends) {
        if (backends.redis() != null) {
            registry.register(govern(new RedisReadTool(
                    backends.redis(), policy.allowedRedisKeyPrefixes())));
        }
    }

    private void registerHttp(ToolRegistry registry, DiagnosisToolBackends backends) {
        if (backends.http() != null) {
            registry.register(govern(new HttpGetTool(backends.http(), policy.allowedHttpHosts())));
        }
    }

    private void registerDubbo(ToolRegistry registry, DiagnosisToolBackends backends) {
        if (backends.dubbo() != null) {
            registry.register(govern(new DubboInvokeTool(
                    backends.dubbo(), policy.allowedDubboAddresses(),
                    policy.allowedDubboMethods())));
        }
    }

    private Tool govern(Tool rawTool) {
        return new TruncatingTool(new GovernedTool(rawTool, governance), truncator);
    }

    public static ToolGovernance safeDefaults() {
        return new ToolGovernance(Duration.ofSeconds(30),
                new DiagnosisToolRedactor(), ToolAuditSink.NO_OP);
    }
}
