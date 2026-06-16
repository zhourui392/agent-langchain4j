package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.RedisReadClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedisReadToolTest {

    private final ExecutionContext ctx = ExecutionContext.at(Paths.get(System.getProperty("user.dir")));
    private final StubRedisReadClient client = new StubRedisReadClient();
    private final RedisReadTool tool = new RedisReadTool(client);

    @Test
    void runsGet() {
        client.result = "myvalue";

        ToolResult result = tool.execute(args("GET mykey"), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("myvalue");
        assertThat(client.lastCommand).isEqualTo("GET mykey");
    }

    @Test
    void allowsCommonReadCommands() {
        client.result = "ok";

        assertThat(tool.execute(args("HGETALL myhash"), ctx).success()).isTrue();
        assertThat(tool.execute(args("SCAN 0 MATCH user:*"), ctx).success()).isTrue();
        assertThat(tool.execute(args("TTL mykey"), ctx).success()).isTrue();
        assertThat(tool.execute(args("LRANGE mylist 0 -1"), ctx).success()).isTrue();
    }

    @Test
    void rejectsSet() {
        ToolResult result = tool.execute(args("SET k v"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("read-only");
        assertThat(client.calls).isZero();
    }

    @Test
    void rejectsDel() {
        assertThat(tool.execute(args("DEL k"), ctx).success()).isFalse();
        assertThat(client.calls).isZero();
    }

    @Test
    void rejectsFlushall() {
        assertThat(tool.execute(args("FLUSHALL"), ctx).success()).isFalse();
        assertThat(client.calls).isZero();
    }

    @Test
    void rejectsKeysScan() {
        ToolResult result = tool.execute(args("KEYS *"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(client.calls).isZero();
    }

    @Test
    void missingCommandRejected() {
        ToolResult result = tool.execute(args(""), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("command");
        assertThat(client.calls).isZero();
    }

    @Test
    void backendErrorReturnsError() {
        client.failure = new IOException("connection refused");

        ToolResult result = tool.execute(args("GET k"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("connection refused");
    }

    @Test
    void isReadOnlyTrue() {
        assertThat(tool.isReadOnly()).isTrue();
    }

    private ToolArguments args(String command) {
        return ToolArguments.of(Map.of("command", command));
    }

    private static final class StubRedisReadClient implements RedisReadClient {
        private String result = "";
        private IOException failure;
        private int calls;
        private String lastCommand;

        @Override
        public String execute(String command, Duration timeout) throws IOException {
            calls++;
            lastCommand = command;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
