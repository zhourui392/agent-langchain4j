package com.anthropic.cclc.infrastructure.context;

import com.anthropic.cclc.domain.context.ContextProvider;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class GitStatusProvider implements ContextProvider {

    @Override
    public String key() {
        return "git_status";
    }

    @Override
    public Optional<String> provide(Path workingDirectory) {
        if (!Files.isDirectory(workingDirectory.resolve(".git"))) {
            return Optional.empty();
        }
        try {
            ProcessResult result = new ProcessExecutor()
                    .command("git", "status", "--short", "--branch")
                    .directory(workingDirectory.toFile())
                    .timeout(5, TimeUnit.SECONDS)
                    .readOutput(true)
                    .exitValueAny()
                    .execute();
            if (result.getExitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(result.outputUTF8().stripTrailing());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isDynamic() {
        return true;
    }
}
