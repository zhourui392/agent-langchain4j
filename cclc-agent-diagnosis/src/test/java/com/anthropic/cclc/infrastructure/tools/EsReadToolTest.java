package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.EsReadClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EsReadToolTest {

    private final ExecutionContext ctx = ExecutionContext.at(Paths.get(System.getProperty("user.dir")));
    private final StubEsReadClient client = new StubEsReadClient();
    private final EsReadTool tool = new EsReadTool(client);

    @Test
    void searchReturnsHits() {
        client.searchResult = "{\"hits\":[1,2,3]}";

        ToolResult result = tool.execute(args(Map.of("op", "search", "index", "logs-2026", "query", "{}")), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("{\"hits\":[1,2,3]}");
        assertThat(client.lastIndex).isEqualTo("logs-2026");
    }

    @Test
    void countReturnsNumber() {
        client.countResult = 42;

        ToolResult result = tool.execute(args(Map.of("op", "count", "index", "logs-2026")), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("42");
    }

    @Test
    void rejectsUnknownOp() {
        ToolResult result = tool.execute(args(Map.of("op", "delete", "index", "logs-2026")), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("op");
        assertThat(client.calls).isZero();
    }

    @Test
    void missingIndexRejected() {
        ToolResult result = tool.execute(args(Map.of("op", "search")), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("index");
    }

    @Test
    void backendErrorReturnsError() {
        client.failure = new IOException("es down");

        ToolResult result = tool.execute(args(Map.of("op", "search", "index", "logs-2026")), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("es down");
    }

    @Test
    void isReadOnlyTrue() {
        assertThat(tool.isReadOnly()).isTrue();
    }

    private ToolArguments args(Map<String, Object> values) {
        return ToolArguments.of(values);
    }

    private static final class StubEsReadClient implements EsReadClient {
        private String searchResult = "";
        private long countResult;
        private String getResult = "";
        private String mappingResult = "";
        private IOException failure;
        private int calls;
        private String lastIndex;

        @Override
        public String search(String index, String queryJson, int size) throws IOException {
            return record(index, searchResult);
        }

        @Override
        public long count(String index, String queryJson) throws IOException {
            record(index, "");
            return countResult;
        }

        @Override
        public String get(String index, String id) throws IOException {
            return record(index, getResult);
        }

        @Override
        public String mapping(String index) throws IOException {
            return record(index, mappingResult);
        }

        private String record(String index, String result) throws IOException {
            calls++;
            lastIndex = index;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
