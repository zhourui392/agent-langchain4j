package com.anthropic.cclc.infrastructure.tools.support;

import java.util.Objects;

public record StringReplacement(String oldString, String newString, boolean replaceAll) {

    public StringReplacement {
        Objects.requireNonNull(oldString, "oldString");
        Objects.requireNonNull(newString, "newString");
        if (oldString.isEmpty()) {
            throw new IllegalArgumentException("oldString must not be empty");
        }
    }

    public int countOccurrences(String text) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(oldString, idx)) != -1) {
            count++;
            idx += oldString.length();
        }
        return count;
    }

    public String applyTo(String text) {
        return replaceAll
                ? text.replace(oldString, newString)
                : replaceFirst(text);
    }

    private String replaceFirst(String text) {
        int idx = text.indexOf(oldString);
        if (idx < 0) {
            return text;
        }
        return text.substring(0, idx) + newString + text.substring(idx + oldString.length());
    }
}
