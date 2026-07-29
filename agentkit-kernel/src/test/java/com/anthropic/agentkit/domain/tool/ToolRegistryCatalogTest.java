package com.anthropic.agentkit.domain.tool;

import com.anthropic.agentkit.testsupport.FakeTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryCatalogTest {

    private final ExecutionContext context = ExecutionContext.at(Path.of("."));

    @Test
    void resolvesDynamicToolsWithExplicitExecutionScope() {
        Tool remote = FakeTool.readOnlyReturning("inventory.read", "remote");
        ToolCatalog catalog = ignored -> new ToolCatalogSnapshot("inventory", List.of(remote));
        ToolRegistry registry = new ToolRegistry().registerCatalog(catalog);

        assertThat(registry.find("inventory.read", context)).isSameAs(remote);
        assertThat(registry.specs(context)).extracting(spec -> spec.name())
                .containsExactly("inventory.read");
    }

    @Test
    void rejectsLocalAndDynamicNamespaceCollision() {
        Tool local = FakeTool.readOnlyReturning("inventory.read", "local");
        Tool remote = FakeTool.readOnlyReturning("inventory.read", "remote");
        ToolCatalog catalog = ignored -> new ToolCatalogSnapshot("inventory", List.of(remote));
        ToolRegistry registry = new ToolRegistry().register(local).registerCatalog(catalog);

        assertThatThrownBy(() -> registry.specs(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory.read", "inventory");
    }

    @Test
    void legacyLookupDoesNotResolveScopeDependentCatalogsImplicitly() {
        Tool remote = FakeTool.readOnlyReturning("inventory.read", "remote");
        ToolRegistry registry = new ToolRegistry().registerCatalog(
                ignored -> new ToolCatalogSnapshot("inventory", List.of(remote)));

        assertThatThrownBy(() -> registry.find("inventory.read"))
                .isInstanceOf(UnknownToolException.class);
        assertThat(registry.specs()).isEmpty();
    }
}
