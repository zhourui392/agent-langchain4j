package com.anthropic.cclc.infrastructure.diagnosis;

import com.anthropic.cclc.infrastructure.tools.support.DubboTelnetClient;
import com.anthropic.cclc.infrastructure.tools.support.EsReadClient;
import com.anthropic.cclc.infrastructure.tools.support.HttpReader;
import com.anthropic.cclc.infrastructure.tools.support.LogQueryClient;
import com.anthropic.cclc.infrastructure.tools.support.MysqlReadClient;
import com.anthropic.cclc.infrastructure.tools.support.RedisReadClient;

/**
 * Host-provided backend clients for diagnosis tools.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record DiagnosisToolBackends(LogQueryClient logQuery,
                                    EsReadClient es,
                                    MysqlReadClient mysql,
                                    RedisReadClient redis,
                                    HttpReader http,
                                    DubboTelnetClient dubbo) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LogQueryClient logQuery;
        private EsReadClient es;
        private MysqlReadClient mysql;
        private RedisReadClient redis;
        private HttpReader http;
        private DubboTelnetClient dubbo;

        public Builder logQuery(LogQueryClient logQuery) {
            this.logQuery = logQuery;
            return this;
        }

        public Builder es(EsReadClient es) {
            this.es = es;
            return this;
        }

        public Builder mysql(MysqlReadClient mysql) {
            this.mysql = mysql;
            return this;
        }

        public Builder redis(RedisReadClient redis) {
            this.redis = redis;
            return this;
        }

        public Builder http(HttpReader http) {
            this.http = http;
            return this;
        }

        public Builder dubbo(DubboTelnetClient dubbo) {
            this.dubbo = dubbo;
            return this;
        }

        public DiagnosisToolBackends build() {
            return new DiagnosisToolBackends(logQuery, es, mysql, redis, http, dubbo);
        }
    }
}
