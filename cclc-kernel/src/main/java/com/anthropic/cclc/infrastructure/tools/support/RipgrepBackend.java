package com.anthropic.cclc.infrastructure.tools.support;

import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RipgrepBackend implements GrepBackend {

    public static boolean isAvailable() {
        try {
            ProcessResult result = new ProcessExecutor()
                    .command(executableName(), "--version")
                    .timeout(2, TimeUnit.SECONDS)
                    .readOutput(true)
                    .exitValueAny()
                    .execute();
            return result.getExitValue() == 0;
        } catch (IOException | InterruptedException | TimeoutException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    @Override
    public GrepResult search(GrepRequest request, Path cwd) {
        List<String> command = buildCommand(request);
        try {
            ProcessResult result = new ProcessExecutor()
                    .command(command)
                    .directory(cwd.toFile())
                    .timeout(60, TimeUnit.SECONDS)
                    .redirectErrorStream(true)
                    .readOutput(true)
                    .exitValueAny()
                    .execute();
            int exit = result.getExitValue();
            if (exit == 0 || exit == 1) {
                return GrepResult.ok(result.outputUTF8());
            }
            return GrepResult.error("ripgrep exit " + exit + ": " + result.outputUTF8());
        } catch (IOException | InterruptedException | TimeoutException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return GrepResult.error("ripgrep error: " + ex.getMessage());
        }
    }

    private static List<String> buildCommand(GrepRequest request) {
        List<String> args = new ArrayList<>();
        args.add(executableName());
        args.add("--with-filename");
        args.add("--line-number");
        if (request.contextLines() > 0) {
            args.add("-C");
            args.add(String.valueOf(request.contextLines()));
        }
        if (request.globFilter() != null && !request.globFilter().isEmpty()) {
            args.add("--glob");
            args.add(request.globFilter());
        }
        args.add("--");
        args.add(request.pattern());
        args.add(".");
        return args;
    }

    private static String executableName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "rg.exe" : "rg";
    }
}
