package com.anthropic.cclc.domain.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    @Test
    void findsToolByName() {
        Tool bash = stubTool("Bash", false);
        ToolRegistry registry = new ToolRegistry().register(bash);

        assertThat(registry.find("Bash")).isEqualTo(bash);
        assertThat(registry.contains("Bash")).isTrue();
    }

    @Test
    void throwsOnUnknownTool() {
        ToolRegistry registry = new ToolRegistry();

        assertThatThrownBy(() -> registry.find("Missing"))
                .isInstanceOf(UnknownToolException.class)
                .hasMessageContaining("Missing");
    }

    @Test
    void rejectsDuplicateRegistration() {
        ToolRegistry registry = new ToolRegistry().register(stubTool("Bash", false));

        assertThatThrownBy(() -> registry.register(stubTool("Bash", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bash");
    }

    @Test
    void registerReturnsRegistryForChaining() {
        ToolRegistry registry = new ToolRegistry()
                .register(stubTool("Bash", false))
                .register(stubTool("Read", true));

        assertThat(registry.names()).containsExactlyInAnyOrder("Bash", "Read");
    }

    @Test
    void toolArgumentsExposesTypedAccessors() {
        ToolArguments args = ToolArguments.of(Map.of("command", "ls", "timeout", 30));

        assertThat(args.getString("command")).isEqualTo("ls");
        assertThat(args.getInt("timeout", 0)).isEqualTo(30);
        assertThat(args.getInt("missing", 99)).isEqualTo(99);
    }

    private static Tool stubTool(String name, boolean readOnly) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean isReadOnly() { return readOnly; }
            @Override public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
                return ToolResult.ok("stub-result");
            }
        };
    }
}
