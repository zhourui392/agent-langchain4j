package com.anthropic.agentkit.application.agent;

import com.anthropic.agentkit.domain.agent.AgentEntryPoint;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentManifest;
import com.anthropic.agentkit.domain.agent.CapabilityDescriptor;
import com.anthropic.agentkit.domain.agent.ConfigKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRegistryTest {

    @Test
    void duplicateAgentIdFailsAtRegistration() {
        AgentManifest<Request, Result> first = manifest("echo", Set.of(), new EchoEntryPoint());
        AgentManifest<Request, Result> duplicate = manifest("echo", Set.of(), new EchoEntryPoint());

        assertThatThrownBy(() -> new AgentRegistry(List.of(first, duplicate), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate", "echo");
    }

    @Test
    void missingRequiredConfigFailsBeforeInvocation() {
        AtomicInteger calls = new AtomicInteger();
        AgentManifest<Request, Result> manifest = manifest(
                "configured", Set.of(ConfigKey.of("service.url")),
                new EchoEntryPoint(calls));
        AgentRegistry registry = new AgentRegistry(List.of(manifest), Set.of());

        assertThatThrownBy(() -> registry.dispatch(
                AgentId.of("configured"), new Request("hello"), Result.class))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("service.url");
        assertThat(calls).hasValue(0);
    }

    @Test
    void dispatchesRegisteredEntryPointWithCheckedResultType() {
        AgentManifest<Request, Result> manifest = manifest(
                "echo", Set.of(ConfigKey.of("service.url")), new EchoEntryPoint());
        AgentRegistry registry = new AgentRegistry(
                List.of(manifest), Set.of(ConfigKey.of("service.url")));

        Result result = registry.dispatch(
                AgentId.of("echo"), new Request("hello"), Result.class);

        assertThat(result.text()).isEqualTo("hello");
        assertThatThrownBy(() -> registry.dispatch(
                AgentId.of("echo"), "wrong request", Result.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Request.class.getName());
    }

    @Test
    void dispatchesDifferentAgentTypesFromSameRegistry() {
        AgentManifest<Request, Result> echo = manifest("echo", Set.of(), new EchoEntryPoint());
        AgentManifest<CountRequest, CountResult> count = new AgentManifest<>(
                AgentId.of("count"), "Count text", new CountEntryPoint(), Set.of(),
                CapabilityDescriptor.none());
        AgentRegistry registry = new AgentRegistry(List.of(echo, count), Set.of());

        Result echoed = registry.dispatch(
                AgentId.of("echo"), new Request("hello"), Result.class);
        CountResult counted = registry.dispatch(
                AgentId.of("count"), new CountRequest("hello"), CountResult.class);

        assertThat(echoed.text()).isEqualTo("hello");
        assertThat(counted.characters()).isEqualTo(5);
    }

    private static AgentManifest<Request, Result> manifest(
            String id, Set<ConfigKey> config, EchoEntryPoint entryPoint) {
        return new AgentManifest<>(AgentId.of(id), "Echo agent", entryPoint,
                config, CapabilityDescriptor.none());
    }

    private record Request(String text) {
    }

    private record Result(String text) {
    }

    private record CountRequest(String text) {
    }

    private record CountResult(int characters) {
    }

    private static final class EchoEntryPoint implements AgentEntryPoint<Request, Result> {
        private final AtomicInteger calls;

        private EchoEntryPoint() {
            this(new AtomicInteger());
        }

        private EchoEntryPoint(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public Class<Request> requestType() {
            return Request.class;
        }

        @Override
        public Class<Result> resultType() {
            return Result.class;
        }

        @Override
        public Result invoke(Request request) {
            calls.incrementAndGet();
            return new Result(request.text());
        }
    }

    private static final class CountEntryPoint
            implements AgentEntryPoint<CountRequest, CountResult> {

        @Override
        public Class<CountRequest> requestType() {
            return CountRequest.class;
        }

        @Override
        public Class<CountResult> resultType() {
            return CountResult.class;
        }

        @Override
        public CountResult invoke(CountRequest request) {
            return new CountResult(request.text().length());
        }
    }
}
