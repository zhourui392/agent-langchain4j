package com.anthropic.agentkit.infrastructure.diagnosis;

import com.anthropic.agentkit.infrastructure.tools.support.HttpEsReadClient;
import com.anthropic.agentkit.infrastructure.tools.support.HttpLogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.JdbcMysqlReadClient;
import com.anthropic.agentkit.infrastructure.tools.support.JdkHttpReader;
import com.anthropic.agentkit.infrastructure.tools.support.SocketDubboTelnetClient;
import com.anthropic.agentkit.infrastructure.tools.support.SocketRedisClient;

import java.util.Objects;

/**
 * Creates diagnosis tool backend clients from host-provided configuration.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class DiagnosisToolBackendsFactory {

    private DiagnosisToolBackendsFactory() {
    }

    public static DiagnosisToolBackends fromConfig(DiagnosisBackendConfig config) {
        Objects.requireNonNull(config, "config");
        DiagnosisToolBackends.Builder builder = DiagnosisToolBackends.builder();
        registerLogQuery(builder, config);
        registerEs(builder, config);
        registerMysql(builder, config);
        registerRedis(builder, config);
        registerHttp(builder, config);
        registerDubbo(builder, config);
        return builder.build();
    }

    private static void registerLogQuery(DiagnosisToolBackends.Builder builder,
                                         DiagnosisBackendConfig config) {
        if (config.logQuery() != null) {
            DiagnosisBackendConfig.LogQueryConfig logQuery = config.logQuery();
            builder.logQuery(new HttpLogQueryClient(logQuery.endpointUrl(), logQuery.headers()));
        }
    }

    private static void registerEs(DiagnosisToolBackends.Builder builder, DiagnosisBackendConfig config) {
        if (config.es() != null) {
            builder.es(new HttpEsReadClient(config.es().baseUrl()));
        }
    }

    private static void registerMysql(DiagnosisToolBackends.Builder builder, DiagnosisBackendConfig config) {
        if (config.mysql() != null) {
            DiagnosisBackendConfig.MysqlConfig mysql = config.mysql();
            builder.mysql(new JdbcMysqlReadClient(mysql.jdbcUrl(), mysql.user(), mysql.password()));
        }
    }

    private static void registerRedis(DiagnosisToolBackends.Builder builder, DiagnosisBackendConfig config) {
        if (config.redis() != null) {
            DiagnosisBackendConfig.RedisConfig redis = config.redis();
            builder.redis(new SocketRedisClient(redis.host(), redis.port(), redis.password(), redis.database()));
        }
    }

    private static void registerHttp(DiagnosisToolBackends.Builder builder, DiagnosisBackendConfig config) {
        if (config.http() != null) {
            builder.http(new JdkHttpReader());
        }
    }

    private static void registerDubbo(DiagnosisToolBackends.Builder builder, DiagnosisBackendConfig config) {
        if (config.dubbo() != null) {
            builder.dubbo(new SocketDubboTelnetClient());
        }
    }
}
