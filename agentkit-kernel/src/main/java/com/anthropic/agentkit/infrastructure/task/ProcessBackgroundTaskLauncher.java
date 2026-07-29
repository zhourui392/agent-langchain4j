package com.anthropic.agentkit.infrastructure.task;

import com.anthropic.agentkit.domain.port.BackgroundTaskLauncher;
import com.anthropic.agentkit.domain.task.TaskHandle;
import com.anthropic.agentkit.domain.task.TaskId;
import com.anthropic.agentkit.domain.task.TaskLaunchSpec;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-process launcher for explicitly scoped OS process tasks. */
public final class ProcessBackgroundTaskLauncher
        implements BackgroundTaskLauncher, AutoCloseable {

    private final ConcurrentMap<TaskId, ProcessTaskHandle> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public TaskHandle launch(TaskLaunchSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (closed.get()) {
            throw new IllegalStateException("background process launcher is closed");
        }
        ProcessTaskHandle handle = new ProcessTaskHandle(spec);
        if (active.putIfAbsent(spec.id(), handle) != null) {
            throw new IllegalStateException("duplicate background task id: " + spec.id());
        }
        try {
            handle.start();
            handle.completion().whenComplete((result, failure) ->
                    active.remove(spec.id(), handle));
            return handle;
        } catch (RuntimeException failure) {
            active.remove(spec.id(), handle);
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<ProcessTaskHandle> handles = List.copyOf(active.values());
        active.clear();
        handles.forEach(ProcessTaskHandle::cancel);
    }
}
