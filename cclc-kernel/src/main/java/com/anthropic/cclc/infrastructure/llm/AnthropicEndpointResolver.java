package com.anthropic.cclc.infrastructure.llm;

/**
 * Normalizes Anthropic-compatible service roots before LangChain4j appends
 * the fixed {@code /messages} path.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
final class AnthropicEndpointResolver {

    private static final String API_VERSION_PATH = "/v1";
    private static final String MESSAGES_PATH = "/messages";

    private AnthropicEndpointResolver() {
    }

    /**
     * @param configuredBaseUrl configured Anthropic-compatible service URL
     * @return base URL that LangChain4j can combine with {@code /messages}
     */
    static String resolveBaseUrl(String configuredBaseUrl) {
        String normalized = trimTrailingSlashes(configuredBaseUrl.trim());
        if (normalized.endsWith(API_VERSION_PATH + MESSAGES_PATH)) {
            return removeSuffix(normalized, MESSAGES_PATH);
        }
        if (normalized.endsWith(API_VERSION_PATH)) {
            return normalized;
        }
        return normalized + API_VERSION_PATH;
    }

    private static String trimTrailingSlashes(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String removeSuffix(String value, String suffix) {
        return value.substring(0, value.length() - suffix.length());
    }
}
