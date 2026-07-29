package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolCatalogSnapshot;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerManagerTest {

    private final ExecutionContext context = ExecutionContext.at(Path.of("."));

    @Test
    void namespacesDiscoveredToolsAndMapsAnnotations() {
        FakeSession session = new FakeSession(List.of(
                descriptor("read", McpToolAnnotations.readOnly()),
                descriptor("delete", McpToolAnnotations.destructive())));

        try (McpServerManager manager = manager(session, 10)) {
            ToolCatalogSnapshot snapshot = manager.snapshot(context);

            assertThat(snapshot.tools()).extracting(Tool::name)
                    .containsExactly("inventory.read", "inventory.delete");
            assertThat(tool(snapshot, "inventory.read").isReadOnly()).isTrue();
            assertThat(tool(snapshot, "inventory.delete").isReadOnly()).isFalse();
            assertThat(tool(snapshot, "inventory.read").safety().idempotent()).isTrue();
            assertThat(tool(snapshot, "inventory.delete").safety().destructive()).isTrue();
        }
    }

    @Test
    void refreshAtomicallyKeepsPreviousCatalogWhenValidationFails() {
        FakeSession session = new FakeSession(List.of(descriptor("read")));
        try (McpServerManager manager = manager(session, 10)) {
            assertThat(manager.snapshot(context).tools()).extracting(Tool::name)
                    .containsExactly("inventory.read");
            session.tools = List.of(descriptor("write"), malformedDescriptor("broken"));

            assertThatThrownBy(() -> manager.refresh(context, "inventory"))
                    .isInstanceOf(McpProtocolException.class)
                    .hasMessageContaining("schema");
            assertThat(manager.snapshot(context).tools()).extracting(Tool::name)
                    .containsExactly("inventory.read");
        }
    }

    @Test
    void largeCatalogExposesOnlyExplicitlySelectedSchemas() {
        List<McpToolDescriptor> tools = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> descriptor("tool-" + index)).toList();
        FakeSession session = new FakeSession(tools);

        try (McpServerManager manager = manager(session, 3)) {
            Tool discover = manager.snapshot(context).tools().getFirst();
            assertThat(manager.snapshot(context).tools()).extracting(Tool::name)
                    .containsExactly("inventory.__discover_tools");

            assertThat(discover.execute(ToolArguments.of(
                    Map.of("names", List.of("tool-77"))), context).status())
                    .isEqualTo(ToolResultStatus.SUCCESS);

            assertThat(manager.snapshot(context).tools()).extracting(Tool::name)
                    .containsExactly("inventory.__discover_tools", "inventory.tool-77");
            assertThat(session.callCount).isZero();
        }
    }

    @Test
    void refreshDoesNotInterruptInvocationHoldingOldAdapter() throws Exception {
        BlockingSession session = new BlockingSession(List.of(descriptor("read")));
        try (McpServerManager manager = manager(session, 10)) {
            Tool oldAdapter = tool(manager.snapshot(context), "inventory.read");
            var result = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> oldAdapter.execute(ToolArguments.empty(), context));
            assertThat(session.started.await(1, TimeUnit.SECONDS)).isTrue();

            session.tools = List.of(descriptor("replacement"));
            manager.refresh(context, "inventory");
            session.release.countDown();

            assertThat(result.get(1, TimeUnit.SECONDS).content()).isEqualTo("old-result");
            assertThat(manager.snapshot(context).tools()).extracting(Tool::name)
                    .containsExactly("inventory.replacement");
        }
    }

    @Test
    void failedCallSettlesWithoutReplayAndNextCallReconnects() {
        FakeSession failed = new FakeSession(List.of(descriptor("write")));
        failed.callFailure = new McpConnectionException("connection lost");
        FakeSession recovered = new FakeSession(List.of(descriptor("write")));
        QueueFactory factory = new QueueFactory(failed, recovered);

        try (McpServerManager manager = manager(factory, 10)) {
            Tool first = tool(manager.snapshot(context), "inventory.write");
            assertThat(first.execute(ToolArguments.empty(), context).status())
                    .isEqualTo(ToolResultStatus.ERROR);
            Tool second = tool(manager.snapshot(context), "inventory.write");
            assertThat(second.execute(ToolArguments.empty(), context).content())
                    .isEqualTo("ok");
            assertThat(failed.callCount).isOne();
            assertThat(recovered.callCount).isOne();
        }
    }

    @Test
    void closeScopeReleasesItsSession() {
        FakeSession session = new FakeSession(List.of(descriptor("read")));
        try (McpServerManager manager = manager(session, 10)) {
            manager.snapshot(context);

            manager.close(context);

            assertThat(session.closed).isTrue();
        }
    }

    @Test
    void authenticatedSessionsArePartitionedByExplicitSecretScope() {
        FakeSession first = new FakeSession(List.of(descriptor("read")));
        FakeSession second = new FakeSession(List.of(descriptor("read")));
        QueueFactory factory = new QueueFactory(first, second);
        ExecutionContext another = ExecutionContext.at(Path.of("another-workspace"));

        try (McpServerManager manager = manager(factory, 10)) {
            manager.snapshot(context);
            manager.snapshot(another);
            manager.close(context);

            assertThat(first.closed).isTrue();
            assertThat(second.closed).isFalse();
        }
    }

    @Test
    void duplicateServerIdIsRejectedBeforeConnection() {
        McpServerConfig first = config(10);
        McpServerConfig duplicate = config(20);

        assertThatThrownBy(() -> new McpServerManager(
                List.of(first, duplicate), ignoredFactory()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory");
    }

    @Test
    void preservesDeclaredServerOrderInCatalogSnapshots() {
        McpServerConfig zeta = McpServerConfig.stdio("zeta", List.of("unused"));
        McpServerConfig alpha = McpServerConfig.stdio("alpha", List.of("unused"));
        McpSessionFactory factory = (config, ignored) ->
                new FakeSession(List.of(descriptor("read")));

        try (McpServerManager manager = new McpServerManager(
                List.of(zeta, alpha), factory)) {
            assertThat(manager.snapshot(context).tools()).extracting(Tool::name)
                    .containsExactly("zeta.read", "alpha.read");
        }
    }

    private static McpServerManager manager(FakeSession session, int eagerLimit) {
        return manager(new QueueFactory(session), eagerLimit);
    }

    private static McpServerManager manager(QueueFactory factory, int eagerLimit) {
        return new McpServerManager(List.of(config(eagerLimit)), factory);
    }

    private static McpServerConfig config(int eagerLimit) {
        return McpServerConfig.stdio("inventory", List.of("unused"))
                .withEagerToolLimit(eagerLimit);
    }

    private static McpToolDescriptor descriptor(String name) {
        return descriptor(name, McpToolAnnotations.readOnly());
    }

    private static McpToolDescriptor descriptor(String name, McpToolAnnotations annotations) {
        return new McpToolDescriptor(name, "Tool " + name,
                "{\"type\":\"object\",\"properties\":{}}", annotations);
    }

    private static McpToolDescriptor malformedDescriptor(String name) {
        return new McpToolDescriptor(name, "Broken", "[]", McpToolAnnotations.readOnly());
    }

    private static Tool tool(ToolCatalogSnapshot snapshot, String name) {
        return snapshot.tools().stream().filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static McpSessionFactory ignoredFactory() {
        return (config, context) -> new FakeSession(List.of());
    }

    private static class QueueFactory implements McpSessionFactory {
        private final Deque<FakeSession> sessions = new ArrayDeque<>();

        private QueueFactory(FakeSession... sessions) {
            this.sessions.addAll(List.of(sessions));
        }

        @Override
        public McpSession open(McpServerConfig config, ExecutionContext context) {
            return sessions.removeFirst();
        }
    }

    private static class FakeSession implements McpSession {
        protected volatile List<McpToolDescriptor> tools;
        private RuntimeException callFailure;
        private int callCount;
        private boolean closed;

        private FakeSession(List<McpToolDescriptor> tools) {
            this.tools = new ArrayList<>(tools);
        }

        @Override
        public List<McpToolDescriptor> discoverTools() {
            return List.copyOf(tools);
        }

        @Override
        public McpCallResult call(
                String toolName, ToolArguments arguments, ExecutionContext context) {
            callCount++;
            if (callFailure != null) {
                throw callFailure;
            }
            return McpCallResult.success("ok");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class BlockingSession extends FakeSession {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingSession(List<McpToolDescriptor> tools) {
            super(tools);
        }

        @Override
        public McpCallResult call(
                String toolName, ToolArguments arguments, ExecutionContext context) {
            started.countDown();
            try {
                assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
                return McpCallResult.success("old-result");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new McpConnectionException("interrupted", failure);
            }
        }
    }
}
