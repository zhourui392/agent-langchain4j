package com.anthropic.agentkit.domain.diagnosis;

/**
 * Stable metadata keys published by diagnosis query tools and evidence.
 *
 * @author alex
 * @since 2026-07-30
 */
public final class DiagnosisToolMetadata {

    public static final String DATA_SOURCE_ID = "diagnosis.dataSourceId";
    public static final String ENVIRONMENT = "diagnosis.environment";
    public static final String SERVICE = "diagnosis.service";
    public static final String QUERY_START = "diagnosis.queryStart";
    public static final String QUERY_END = "diagnosis.queryEnd";
    public static final String MATCHED = "diagnosis.matched";
    public static final String RETURNED = "diagnosis.returned";
    public static final String TRUNCATED = "diagnosis.truncated";
    public static final String DURATION_MS = "diagnosis.durationMs";
    public static final String BACKEND_STATUS = "diagnosis.backendStatus";
    public static final String ERROR_CODE = "diagnosis.errorCode";
    public static final String RETRY_COUNT = "diagnosis.retryCount";

    private DiagnosisToolMetadata() {
    }
}
