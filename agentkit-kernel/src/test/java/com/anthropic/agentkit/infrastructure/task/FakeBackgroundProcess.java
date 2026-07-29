package com.anthropic.agentkit.infrastructure.task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Subprocess fixture for background streaming and process-tree reclamation tests. */
public final class FakeBackgroundProcess {

    private FakeBackgroundProcess() { }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "stream" -> stream();
            case "tree" -> tree(Path.of(args[1]));
            case "child" -> waitForever();
            default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
        }
    }

    private static void stream() throws Exception {
        System.out.println("first-line");
        Thread.sleep(500);
        System.out.println("second-line");
    }

    private static void tree(Path marker) throws Exception {
        Process child = new ProcessBuilder(childCommand()).start();
        Files.writeString(marker, ProcessHandle.current().pid() + "\n" + child.pid() + "\n");
        System.out.println("tree-ready");
        waitForever();
    }

    private static List<String> childCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                FakeBackgroundProcess.class.getName(), "child", "unused");
    }

    private static void waitForever() throws Exception {
        while (true) {
            Thread.sleep(1_000);
        }
    }
}
