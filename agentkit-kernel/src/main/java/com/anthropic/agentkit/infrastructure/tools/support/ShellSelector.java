package com.anthropic.agentkit.infrastructure.tools.support;

import java.util.List;

public final class ShellSelector {

    private static final List<String> SHELL_PREFIX = detect();

    private ShellSelector() {
    }

    public static List<String> commandFor(String command) {
        return List.of(SHELL_PREFIX.get(0), SHELL_PREFIX.get(1), command);
    }

    public static String shellName() {
        return SHELL_PREFIX.get(0);
    }

    private static List<String> detect() {
        if (isBashOnPath()) {
            return List.of("bash", "-c");
        }
        if (isWindows()) {
            return List.of("cmd.exe", "/c");
        }
        return List.of("sh", "-c");
    }

    private static boolean isBashOnPath() {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        String separator = System.getProperty("path.separator", ":");
        String exec = isWindows() ? "bash.exe" : "bash";
        for (String dir : path.split(separator)) {
            java.io.File candidate = new java.io.File(dir, exec);
            if (candidate.isFile() && candidate.canExecute()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
