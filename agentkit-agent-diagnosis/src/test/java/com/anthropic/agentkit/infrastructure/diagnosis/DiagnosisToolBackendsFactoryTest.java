package com.anthropic.agentkit.infrastructure.diagnosis;

import com.anthropic.agentkit.domain.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosisToolBackendsFactoryTest {

    @Test
    void wiresAllBackendsFromFullConfig() {
        DiagnosisBackendConfig config = new DiagnosisBackendConfig(
                new DiagnosisBackendConfig.EsConfig("http://es.local:9200"),
                new DiagnosisBackendConfig.MysqlConfig("jdbc:mysql://db/order", "qpon", "secret"),
                new DiagnosisBackendConfig.RedisConfig("redis.local", 6379, "secret", 0),
                new DiagnosisBackendConfig.LogQueryConfig("https://log.local/search", Map.of("Authorization", "Bearer x")),
                new DiagnosisBackendConfig.HttpConfig(),
                new DiagnosisBackendConfig.DubboConfig());

        DiagnosisToolBackends backends = DiagnosisToolBackendsFactory.fromConfig(config);
        ToolRegistry registry = new DiagnoseToolFactory().create(backends);

        assertThat(registry.names()).containsExactly(
                "LogQuery", "EsRead", "MysqlRead", "RedisRead", "HttpGet", "DubboInvoke");
    }

    @Test
    void skipsAbsentBackends() {
        DiagnosisBackendConfig config = new DiagnosisBackendConfig(
                null,
                new DiagnosisBackendConfig.MysqlConfig("jdbc:mysql://db/order", "qpon", "secret"),
                null,
                null,
                null,
                null);

        DiagnosisToolBackends backends = DiagnosisToolBackendsFactory.fromConfig(config);
        ToolRegistry registry = new DiagnoseToolFactory().create(backends);

        assertThat(registry.names()).containsExactly("MysqlRead");
    }

    @Test
    void rejectsBlankCredentialFields() {
        assertThatThrownBy(() -> new DiagnosisBackendConfig.MysqlConfig(" ", "qpon", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbcUrl");
    }

    @Test
    void backendConfigurationStringProjectionNeverContainsConnectionsOrCredentials() {
        DiagnosisBackendConfig config = new DiagnosisBackendConfig(
                new DiagnosisBackendConfig.EsConfig("https://es.must-not-survive"),
                new DiagnosisBackendConfig.MysqlConfig(
                        "jdbc:mysql://db.must-not-survive/order", "user-must-not-survive",
                        "password-must-not-survive"),
                new DiagnosisBackendConfig.RedisConfig(
                        "redis.must-not-survive", 6379, "redis-password-must-not-survive", 0),
                new DiagnosisBackendConfig.LogQueryConfig(
                        "https://logs.must-not-survive/query",
                        Map.of("Authorization", "Bearer must-not-survive")), null, null);

        assertThat(config.toString()).doesNotContain(
                "must-not-survive", "Bearer", "jdbc:mysql", "https://", "Authorization")
                .contains("***");
    }
}
