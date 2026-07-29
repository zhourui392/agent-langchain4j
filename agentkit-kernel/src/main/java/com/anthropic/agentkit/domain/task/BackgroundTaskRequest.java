package com.anthropic.agentkit.domain.task;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Provider-neutral request to run an explicit command outside the calling tool thread. */
public record BackgroundTaskRequest(
        String description, List<String> command, Duration timeout) {

    public BackgroundTaskRequest {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("task description must not be blank");
        }
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (command.isEmpty() || command.stream().anyMatch(BackgroundTaskRequest::blank)) {
            throw new IllegalArgumentException("task command must not be empty or blank");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("task timeout must be positive");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
