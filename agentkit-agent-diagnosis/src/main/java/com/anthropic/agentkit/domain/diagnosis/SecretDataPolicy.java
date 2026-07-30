package com.anthropic.agentkit.domain.diagnosis;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared fail-closed policy for text that can enter diagnosis prompts, state, or public views.
 *
 * @author alex
 */
public final class SecretDataPolicy {

    static final String REDACTED = "***";

    private static final Set<String> SENSITIVE_KEY_TOKENS = Set.of(
            "key", "apikey", "accesskey", "privatekey", "token", "password",
            "secret", "authorization", "cookie", "credential");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)['\"]?(api[_-]?key|access[_-]?key|private[_-]?key|token|password|secret|credential)"
                    + "['\"]?"
                    + "\\s*[:=]\\s*(?:['\"]?)[^\\s,'\"}]+"
    );
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)(?:authorization\\s*[:=]\\s*)?\\b(?:bearer|basic|apikey)\\s+[^\\s,;]+"
    );
    private static final Pattern SAFE_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)['\"]?(?:api[_-]?key|access[_-]?key|private[_-]?key|token|password|secret|credential)"
                    + "['\"]?\\s*[:=]\\s*['\"]?(?:\\*{3}|\\[redacted])['\"]?"
                    + "(?=\\s|\\\\[nrt]|[,;}]|$)"
    );
    private static final Pattern SAFE_AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)(?:authorization\\s*[:=]\\s*)?\\b(?:bearer|basic|apikey)\\s+"
                    + "(?:\\*{3}|\\[redacted])['\"]?(?=\\s|\\\\[nrt]|[,;}]|$)"
    );
    private static final Pattern OPENAI_STYLE_SECRET = Pattern.compile(
            "(?i)\\bsk-[a-z0-9_-]{8,}\\b"
    );

    private SecretDataPolicy() {
    }

    static boolean sensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        String words = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return Arrays.stream(words.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> !token.isBlank())
                .anyMatch(SENSITIVE_KEY_TOKENS::contains);
    }

    public static String sanitize(String value) {
        String text = value == null ? "" : value.trim();
        if (containsSecret(text)) {
            return REDACTED;
        }
        return text;
    }

    public static Object sanitize(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence text) {
            return sanitize(text.toString());
        }
        if (value instanceof Map<?, ?> values) {
            LinkedHashMap<String, Object> safe = new LinkedHashMap<>();
            values.forEach((key, nestedValue) -> {
                String cleanKey = key == null ? "" : key.toString().trim();
                if (!sensitiveKey(cleanKey)) {
                    safe.put(cleanKey, sanitize(nestedValue));
                }
            });
            return Map.copyOf(safe);
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(SecretDataPolicy::sanitize).toList();
        }
        if (value.getClass().isArray()) {
            return sanitizeArray(value);
        }
        return sanitize(value.toString());
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return sanitize(value);
    }

    static List<String> sanitizeList(Collection<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(value -> required(value, field)).toList();
    }

    static Set<String> sanitizeSet(Collection<String> values, String field) {
        if (values == null) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(required(value, field)));
        return Set.copyOf(result);
    }

    static List<String> tokens(String key) {
        if (key == null) {
            return List.of();
        }
        String words = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return List.of(words.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"));
    }

    private static List<Object> sanitizeArray(Object array) {
        int length = Array.getLength(array);
        java.util.ArrayList<Object> safe = new java.util.ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            safe.add(sanitize(Array.get(array, index)));
        }
        return List.copyOf(safe);
    }

    private static boolean containsSecret(String text) {
        String unchecked = SAFE_SECRET_ASSIGNMENT.matcher(text).replaceAll("");
        unchecked = SAFE_AUTHORIZATION_VALUE.matcher(unchecked).replaceAll("");
        return SECRET_ASSIGNMENT.matcher(unchecked).find()
                || AUTHORIZATION_VALUE.matcher(unchecked).find()
                || OPENAI_STYLE_SECRET.matcher(unchecked).find();
    }
}
