package com.anthropic.cclc.infrastructure.diagnosis;

import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.infrastructure.tools.DubboInvokeTool;
import com.anthropic.cclc.infrastructure.tools.EsReadTool;
import com.anthropic.cclc.infrastructure.tools.HttpGetTool;
import com.anthropic.cclc.infrastructure.tools.LogQueryTool;
import com.anthropic.cclc.infrastructure.tools.MysqlReadTool;
import com.anthropic.cclc.infrastructure.tools.RedisReadTool;
import com.anthropic.cclc.infrastructure.tools.TruncatingTool;
import com.anthropic.cclc.infrastructure.tools.governance.GovernedTool;
import com.anthropic.cclc.infrastructure.tools.governance.ToolGovernance;
import com.anthropic.cclc.infrastructure.tools.support.ToolResultTruncator;

import java.util.Objects;

/**
 * Assembles production diagnosis tools with kernel governance wrappers.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class DiagnoseToolFactory {

    private final ToolGovernance governance;
    private final ToolResultTruncator truncator;

    public DiagnoseToolFactory() {
        this(ToolGovernance.defaults(), ToolResultTruncator.withDefaults());
    }

    public DiagnoseToolFactory(ToolGovernance governance, ToolResultTruncator truncator) {
        this.governance = Objects.requireNonNull(governance, "governance");
        this.truncator = Objects.requireNonNull(truncator, "truncator");
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
            registry.register(govern(new EsReadTool(backends.es())));
        }
    }

    private void registerMysql(ToolRegistry registry, DiagnosisToolBackends backends) {
        if (backends.mysql() != null) {
            registry.register(govern(new MysqlReadTool(backends.mysql())));
        }
    }

    private void registerRedis(ToolRegistry registry, DiagnosisToolBackends backends) {
        if (backends.redis() != null) {
            registry.register(govern(new RedisReadTool(backends.redis())));
        }
    }

    private void registerHttp(ToolRegistry registry, DiagnosisToolBackends backends) {
        if (backends.http() != null) {
            registry.register(govern(new HttpGetTool(backends.http())));
        }
    }

    private void registerDubbo(ToolRegistry registry, DiagnosisToolBackends backends) {
        if (backends.dubbo() != null) {
            registry.register(govern(new DubboInvokeTool(backends.dubbo())));
        }
    }

    private Tool govern(Tool rawTool) {
        return new TruncatingTool(new GovernedTool(rawTool, governance), truncator);
    }
}
