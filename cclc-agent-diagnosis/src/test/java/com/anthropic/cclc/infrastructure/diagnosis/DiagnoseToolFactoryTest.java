package com.anthropic.cclc.infrastructure.diagnosis;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.DubboTelnetClient;
import com.anthropic.cclc.infrastructure.tools.support.EsReadClient;
import com.anthropic.cclc.infrastructure.tools.support.HttpReader;
import com.anthropic.cclc.infrastructure.tools.support.LogQueryClient;
import com.anthropic.cclc.infrastructure.tools.support.LogQueryRequest;
import com.anthropic.cclc.infrastructure.tools.support.MysqlReadClient;
import com.anthropic.cclc.infrastructure.tools.support.RedisReadClient;
import com.anthropic.cclc.infrastructure.tools.governance.ToolAuditEvent;
import com.anthropic.cclc.infrastructure.tools.governance.ToolGovernance;
import com.anthropic.cclc.infrastructure.tools.support.ToolResultTruncator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnoseToolFactoryTest {

    private final List<ToolAuditEvent> auditEvents = new ArrayList<>();

    @Test
    void registersGovernedDiagnosisToolsForProvidedBackends() {
        DiagnosisToolBackends backends = DiagnosisToolBackends.builder()
                .logQuery(request -> "token=secret")
                .mysql((sql, maxRows) -> "mysql")
                .redis((command, timeout) -> "redis")
                .es(new StubEsReadClient())
                .http((url, headers, timeout) -> new HttpReader.HttpResponseView(200, "http"))
                .dubbo((address, invocation, timeout) -> "dubbo")
                .build();
        DiagnoseToolFactory factory = new DiagnoseToolFactory(governance(), ToolResultTruncator.withDefaults());

        ToolRegistry registry = factory.create(backends);

        assertThat(registry.names()).containsExactly(
                "LogQuery", "EsRead", "MysqlRead", "RedisRead", "HttpGet", "DubboInvoke");
        ToolResult result = registry.find("LogQuery").execute(
                ToolArguments.of(Map.of("traceId", "trace-1")), context());
        assertThat(result.content()).isEqualTo("token=***");
        assertThat(auditEvents).singleElement().satisfies(event -> {
            assertThat(event.toolName()).isEqualTo("LogQuery");
            assertThat(event.success()).isTrue();
        });
    }

    @Test
    void skipsToolsWithoutProvidedBackends() {
        DiagnosisToolBackends backends = DiagnosisToolBackends.builder()
                .logQuery(request -> "log")
                .build();

        ToolRegistry registry = new DiagnoseToolFactory(governance(), ToolResultTruncator.withDefaults())
                .create(backends);

        assertThat(registry.names()).containsExactly("LogQuery");
    }

    private ToolGovernance governance() {
        return new ToolGovernance(
                Duration.ofSeconds(1),
                content -> content.replace("secret", "***"),
                auditEvents::add);
    }

    private static ExecutionContext context() {
        return ExecutionContext.at(Paths.get(System.getProperty("user.dir")));
    }

    private static final class StubEsReadClient implements EsReadClient {

        @Override
        public String search(String index, String queryJson, int size) throws IOException {
            return "search";
        }

        @Override
        public long count(String index, String queryJson) throws IOException {
            return 1;
        }

        @Override
        public String get(String index, String id) throws IOException {
            return "get";
        }

        @Override
        public String mapping(String index) throws IOException {
            return "mapping";
        }
    }
}
