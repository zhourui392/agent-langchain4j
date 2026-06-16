package com.anthropic.agentkit.infrastructure.diagnosis;

import java.util.Map;

/**
 * Host-supplied connection configuration for diagnosis tool backends.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public record DiagnosisBackendConfig(EsConfig es,
                                     MysqlConfig mysql,
                                     RedisConfig redis,
                                     LogQueryConfig logQuery,
                                     HttpConfig http,
                                     DubboConfig dubbo) {

    public record EsConfig(String baseUrl) {
        public EsConfig {
            baseUrl = required(baseUrl, "baseUrl");
        }
    }

    public record MysqlConfig(String jdbcUrl, String user, String password) {
        public MysqlConfig {
            jdbcUrl = required(jdbcUrl, "jdbcUrl");
            user = required(user, "user");
            password = required(password, "password");
        }
    }

    public record RedisConfig(String host, int port, String password, int database) {
        public RedisConfig {
            host = required(host, "host");
            if (port <= 0) {
                throw new IllegalArgumentException("port must be positive");
            }
            if (database < 0) {
                throw new IllegalArgumentException("database must be non-negative");
            }
        }
    }

    public record LogQueryConfig(String endpointUrl, Map<String, String> headers) {
        public LogQueryConfig {
            endpointUrl = required(endpointUrl, "endpointUrl");
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public record HttpConfig() {
    }

    public record DubboConfig() {
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
