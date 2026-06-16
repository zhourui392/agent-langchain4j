package com.anthropic.agentkit.infrastructure.tools.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class DiffRenderer {

    private static final Pattern LINE_SPLIT = Pattern.compile("\\r?\\n");

    private DiffRenderer() {
    }

    public static String unifiedDiff(String oldText, String newText, String label) {
        List<String> oldLines = split(oldText);
        List<String> newLines = split(newText);
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(label).append('\n');
        out.append("+++ ").append(label).append('\n');
        out.append("@@ -1,").append(oldLines.size())
                .append(" +1,").append(newLines.size()).append(" @@\n");
        for (String line : oldLines) {
            out.append('-').append(line).append('\n');
        }
        for (String line : newLines) {
            out.append('+').append(line).append('\n');
        }
        return out.toString();
    }

    private static List<String> split(String text) {
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>();
        for (String part : LINE_SPLIT.split(text, -1)) {
            lines.add(part);
        }
        return lines;
    }
}
