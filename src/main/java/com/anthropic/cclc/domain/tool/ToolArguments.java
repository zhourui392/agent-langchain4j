package com.anthropic.cclc.domain.tool;

import java.util.Map;
import java.util.Objects;

public record ToolArguments(Map<String, Object> values) {

    public ToolArguments {
        Objects.requireNonNull(values, "values");
        values = Map.copyOf(values);
    }

    public static ToolArguments of(Map<String, Object> values) {
        return new ToolArguments(values);
    }

    public static ToolArguments empty() {
        return new ToolArguments(Map.of());
    }

    public String getString(String key) {
        Object v = values.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing argument: " + key);
        }
        return v.toString();
    }

    public String getString(String key, String defaultValue) {
        Object v = values.get(key);
        return v == null ? defaultValue : v.toString();
    }

    public int getInt(String key, int defaultValue) {
        Object v = values.get(key);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object v = values.get(key);
        return v instanceof Boolean b ? b : defaultValue;
    }
}
