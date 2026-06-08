package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.DubboTelnetClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DubboInvokeToolTest {

    private final ExecutionContext ctx = ExecutionContext.at(Paths.get(System.getProperty("user.dir")));
    private final StubDubboTelnetClient client = new StubDubboTelnetClient();
    private final DubboInvokeTool tool = new DubboInvokeTool(client);

    @Test
    void invokesReadMethod() {
        client.response = "result: {\"id\":1}";

        ToolResult result = tool.execute(
                args("10.0.0.1:20880", "com.x.UserService.getUser(1)"), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("result: {\"id\":1}");
        assertThat(client.lastInvocation).isEqualTo("com.x.UserService.getUser(1)");
    }

    @Test
    void rejectsWriteMethod() {
        ToolResult result = tool.execute(
                args("10.0.0.1:20880", "com.x.UserService.deleteUser(1)"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("read");
        assertThat(client.calls).isZero();
    }

    @Test
    void rejectsSaveMethod() {
        ToolResult result = tool.execute(
                args("10.0.0.1:20880", "com.x.OrderService.saveOrder(1)"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(client.calls).isZero();
    }

    @Test
    void missingAddressRejected() {
        ToolResult result = tool.execute(args("", "com.x.S.getX()"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("address");
        assertThat(client.calls).isZero();
    }

    @Test
    void missingInvocationRejected() {
        ToolResult result = tool.execute(args("10.0.0.1:20880", ""), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("invocation");
    }

    @Test
    void backendErrorReturnsError() {
        client.failure = new IOException("connection refused");

        ToolResult result = tool.execute(
                args("10.0.0.1:20880", "com.x.UserService.queryUsers()"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("connection refused");
    }

    @Test
    void isReadOnlyTrue() {
        assertThat(tool.isReadOnly()).isTrue();
    }

    private ToolArguments args(String address, String invocation) {
        return ToolArguments.of(Map.of("address", address, "invocation", invocation));
    }

    private static final class StubDubboTelnetClient implements DubboTelnetClient {
        private String response = "";
        private IOException failure;
        private int calls;
        private String lastInvocation;

        @Override
        public String invoke(String address, String invocation, Duration timeout) throws IOException {
            calls++;
            lastInvocation = invocation;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
