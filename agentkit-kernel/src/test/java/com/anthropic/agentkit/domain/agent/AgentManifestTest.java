package com.anthropic.agentkit.domain.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentManifestTest {

    @Test
    void preservesTypedEntryPointAndImmutableCapabilities() {
        LinkedHashSet<ConfigKey> config = new LinkedHashSet<>(Set.of(ConfigKey.of("endpoint")));
        LinkedHashSet<String> terminalTools = new LinkedHashSet<>(Set.of("submit_result"));
        EchoEntryPoint entryPoint = new EchoEntryPoint();

        AgentManifest<EchoRequest, EchoResult> manifest = new AgentManifest<>(
                AgentId.of("echo"), "Echo a request", entryPoint, config,
                new CapabilityDescriptor(ToolCapabilitySet.of("Read"), terminalTools));
        config.clear();
        terminalTools.clear();

        assertThat(manifest.entryPoint()).isSameAs(entryPoint);
        assertThat(manifest.requiredConfigKeys()).containsExactly(ConfigKey.of("endpoint"));
        assertThat(manifest.capabilities().allowedTools().names()).containsExactly("Read");
        assertThat(manifest.capabilities().terminalTools()).containsExactly("submit_result");
        assertThat(entryPoint.requestType()).isEqualTo(EchoRequest.class);
        assertThat(entryPoint.resultType()).isEqualTo(EchoResult.class);
    }

    @Test
    void rejectsBlankManifestAndConfigMetadata() {
        assertThatThrownBy(() -> ConfigKey.of(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config key");
        assertThatThrownBy(() -> new AgentManifest<>(
                AgentId.of("echo"), " ", new EchoEntryPoint(), Set.of(),
                CapabilityDescriptor.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    private record EchoRequest(String text) {
    }

    private record EchoResult(String text) {
    }

    private static final class EchoEntryPoint implements AgentEntryPoint<EchoRequest, EchoResult> {

        @Override
        public Class<EchoRequest> requestType() {
            return EchoRequest.class;
        }

        @Override
        public Class<EchoResult> resultType() {
            return EchoResult.class;
        }

        @Override
        public EchoResult invoke(EchoRequest request) {
            return new EchoResult(request.text());
        }
    }
}
