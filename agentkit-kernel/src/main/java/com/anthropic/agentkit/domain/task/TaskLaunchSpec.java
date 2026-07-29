package com.anthropic.agentkit.domain.task;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Fully scoped command handed to a background-task launcher port. */
public record TaskLaunchSpec(
        TaskId id,
        TaskScope scope,
        List<String> command,
        Path workingDirectory,
        Duration timeout) {

    public TaskLaunchSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("launch command must not be empty or blank");
        }
        workingDirectory = Objects.requireNonNull(
                workingDirectory, "workingDirectory").toAbsolutePath().normalize();
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("launch timeout must be positive");
        }
    }
}
