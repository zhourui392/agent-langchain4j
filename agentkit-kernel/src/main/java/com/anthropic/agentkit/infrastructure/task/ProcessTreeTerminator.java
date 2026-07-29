package com.anthropic.agentkit.infrastructure.task;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Best-effort cross-platform reclamation of a process and all observed descendants. */
final class ProcessTreeTerminator {

    private static final Duration GRACE = Duration.ofMillis(250);

    private ProcessTreeTerminator() { }

    static void terminate(Process process) {
        if (process == null) {
            return;
        }
        List<ProcessHandle> tree = process.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        tree.forEach(ProcessTreeTerminator::destroy);
        destroy(process.toHandle());
        await(tree, process.toHandle());
        tree.stream().filter(ProcessHandle::isAlive)
                .forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        await(tree, process.toHandle());
    }

    private static void destroy(ProcessHandle handle) {
        if (handle.isAlive()) {
            handle.destroy();
        }
    }

    private static void await(List<ProcessHandle> descendants, ProcessHandle root) {
        descendants.forEach(ProcessTreeTerminator::await);
        await(root);
    }

    private static void await(ProcessHandle handle) {
        if (!handle.isAlive()) {
            return;
        }
        try {
            handle.onExit().get(GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // A second forced pass follows when graceful termination does not finish.
        }
    }
}
