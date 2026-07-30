package com.anthropic.agentkit.domain.diagnosis;

/**
 * Logical kind of a host-bound diagnosis data source.
 *
 * @author alex
 */
public enum DataSourceType {
    LOG,
    ELASTICSEARCH,
    MYSQL,
    REDIS,
    HTTP,
    DUBBO,
    OTHER
}
