package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.HttpReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HttpGetToolTest {

    private final ExecutionContext ctx = ExecutionContext.at(Paths.get(System.getProperty("user.dir")));

    @Test
    void successFormatsStatusAndBody() {
        RecordingHttpReader reader = RecordingHttpReader.returning(200, "OK-body");
        HttpGetTool tool = new HttpGetTool(reader);

        ToolResult result = tool.execute(args("https://svc.local/health"), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("HTTP 200\nOK-body");
        assertThat(reader.lastUrl).isEqualTo("https://svc.local/health");
    }

    @Test
    void rejectsNonHttpUrl() {
        RecordingHttpReader reader = RecordingHttpReader.returning(200, "x");
        HttpGetTool tool = new HttpGetTool(reader);

        ToolResult result = tool.execute(args("ftp://svc.local/file"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("http");
        assertThat(reader.calls).isZero();
    }

    @Test
    void isReadOnlyTrue() {
        assertThat(new HttpGetTool(RecordingHttpReader.returning(200, "x")).isReadOnly()).isTrue();
    }

    @Test
    void backendErrorReturnsError() {
        RecordingHttpReader reader = RecordingHttpReader.throwing(new IOException("connection refused"));
        HttpGetTool tool = new HttpGetTool(reader);

        ToolResult result = tool.execute(args("http://svc.local/health"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("connection refused");
    }

    @Test
    void rejectsHostOutsideAllowlist() {
        RecordingHttpReader reader = RecordingHttpReader.returning(200, "x");
        HttpGetTool tool = new HttpGetTool(reader, Set.of("svc.local"));

        ToolResult result = tool.execute(args("https://evil.local/health"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("allowlisted");
        assertThat(reader.calls).isZero();
    }

    @Test
    void allowsConfiguredHost() {
        RecordingHttpReader reader = RecordingHttpReader.returning(200, "OK");
        HttpGetTool tool = new HttpGetTool(reader, Set.of("svc.local"));

        ToolResult result = tool.execute(args("https://svc.local/health"), ctx);

        assertThat(result.success()).isTrue();
        assertThat(reader.calls).isEqualTo(1);
    }

    private ToolArguments args(String url) {
        return ToolArguments.of(Map.of("url", url));
    }

    private static final class RecordingHttpReader implements HttpReader {
        private final int status;
        private final String body;
        private final IOException failure;
        private int calls;
        private String lastUrl;

        private RecordingHttpReader(int status, String body, IOException failure) {
            this.status = status;
            this.body = body;
            this.failure = failure;
        }

        static RecordingHttpReader returning(int status, String body) {
            return new RecordingHttpReader(status, body, null);
        }

        static RecordingHttpReader throwing(IOException failure) {
            return new RecordingHttpReader(0, "", failure);
        }

        @Override
        public HttpResponseView get(String url, Map<String, String> headers, Duration timeout) throws IOException {
            calls++;
            lastUrl = url;
            if (failure != null) {
                throw failure;
            }
            return new HttpResponseView(status, body);
        }
    }
}
