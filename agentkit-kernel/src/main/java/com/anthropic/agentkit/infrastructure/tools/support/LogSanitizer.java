package com.anthropic.agentkit.infrastructure.tools.support;

import com.anthropic.agentkit.domain.tool.ToolArguments;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * Keeps debug logging useful without writing raw tool payloads to disk.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class LogSanitizer {

    private static final int MAX_SUMMARY_CHARS = 160;

    private LogSanitizer() {
    }

    public static String summarizeArgs(ToolArguments args) {
        StringBuilder summary = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : args.values().entrySet()) {
            if (!first) {
                summary.append(", ");
            }
            first = false;
            summary.append(entry.getKey()).append('=').append(summarizeValue(entry.getValue()));
        }
        summary.append('}');
        return truncate(summary.toString(), MAX_SUMMARY_CHARS);
    }

    public static String summarizeCommand(String command) {
        return truncate(maskSensitive(command), MAX_SUMMARY_CHARS);
    }

    public static String summarizeSql(String sql) {
        return truncate(maskSensitive(sql).replaceAll("'[^']*'", "'?'"), MAX_SUMMARY_CHARS);
    }

    public static String stripQuery(String url) {
        try {
            URI uri = new URI(url);
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, uri.getFragment()).toString();
        } catch (URISyntaxException ex) {
            int queryIndex = url.indexOf('?');
            return truncate(queryIndex >= 0 ? url.substring(0, queryIndex) : url, MAX_SUMMARY_CHARS);
        }
    }

    public static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 14)) + "...[truncated]";
    }

    private static String summarizeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String text = maskSensitive(String.valueOf(value));
        return '"' + truncate(text, 48) + '"';
    }

    private static String maskSensitive(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("(?i)(api[_-]?key|token|password|secret)=\\S+", "$1=***")
                .replaceAll("(?i)(api[_-]?key|token|password|secret)\\s*[:=]\\s*[^\\s,;]+", "$1=***")
                .replaceAll("(?i)(--?(?:api[_-]?key|token|password|secret))\\s+[^\\s,;]+", "$1 ***")
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+", "$1***");
    }
}
