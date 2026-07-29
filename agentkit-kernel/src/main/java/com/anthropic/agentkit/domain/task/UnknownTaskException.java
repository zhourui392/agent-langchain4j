package com.anthropic.agentkit.domain.task;

/** Same failure for absent and wrong-scope tasks to avoid ownership probing. */
public final class UnknownTaskException extends RuntimeException {

    public UnknownTaskException(TaskId id) {
        super("unknown background task: " + id);
    }
}
