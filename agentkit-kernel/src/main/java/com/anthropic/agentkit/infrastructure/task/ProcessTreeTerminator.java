package com.anthropic.agentkit.infrastructure.task;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Best-effort cross-platform reclamation of a process and all observed descendants. */
final class ProcessTreeTerminator {

    private static final Duration GRACE = Duration.ofMillis(250);

    private ProcessTreeTerminator() { }

    static void terminate(Process process) {
        if (process == null) {
            return;
        }
        ObservedProcessTree tree = ObservedProcessTree.capture(process);
        tree.destroyGracefully();
        tree.awaitExit();
        tree.destroySurvivorsForcibly();
        tree.awaitExit();
    }

    private static void destroy(ProcessHandle handle) {
        if (handle.isAlive()) {
            handle.destroy();
        }
    }

    private static void await(ProcessHandle handle, long deadlineNanos) {
        if (!handle.isAlive()) {
            return;
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
        } catch (Exception ignored) {
            // A second forced pass follows when graceful termination does not finish.
        }
    }

    private static final class ObservedProcessTree {

        private final ProcessHandle root;
        private final Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();

        private ObservedProcessTree(ProcessHandle root) {
            this.root = root;
            observeDescendants();
        }

        private static ObservedProcessTree capture(Process process) {
            return new ObservedProcessTree(process.toHandle());
        }

        private void destroyGracefully() {
            observeDescendants();
            descendants.values().forEach(ProcessTreeTerminator::destroy);
            observeDescendants();
            descendants.values().forEach(ProcessTreeTerminator::destroy);
            destroy(root);
        }

        private void destroySurvivorsForcibly() {
            observeDescendants();
            descendants.values().stream().filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            if (root.isAlive()) {
                root.destroyForcibly();
            }
        }

        private void awaitExit() {
            long deadline = System.nanoTime() + GRACE.toNanos();
            descendants.values().forEach(handle ->
                    ProcessTreeTerminator.await(handle, deadline));
            ProcessTreeTerminator.await(root, deadline);
        }

        private void observeDescendants() {
            if (!root.isAlive()) {
                return;
            }
            root.descendants().forEach(handle -> descendants.put(handle.pid(), handle));
        }
    }
}
