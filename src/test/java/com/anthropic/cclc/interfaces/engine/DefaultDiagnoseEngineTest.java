package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.port.ChatRequest;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import com.anthropic.cclc.testsupport.FakeTool;
import com.anthropic.cclc.testsupport.StubLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultDiagnoseEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
    private final ExecutorService runner = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        runner.shutdownNow();
    }

    @Test
    void streamsTextThenExitsZero() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("hello"));
        DiagnoseEngine engine = new DefaultDiagnoseEngine(llm, new ToolRegistry());

        engine.runStream(request(List.of()), lines::add, exitCode::set);

        assertThat(exitCode).hasValue(0);
        assertThat(topTypes()).containsSubsequence("system", "stream_event", "result");
        assertThat(firstTextDelta()).isEqualTo("hello");
        assertThat(engine.isRunning("s-1")).isFalse();
    }

    @Test
    void runsToolAndStreamsToolResult() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(
                        new ToolUseRequest(new ToolUseId("tu-1"), "LogQuery", "{}"))))
                .enqueue(AiMessage.text("done"));
        ToolRegistry tools = new ToolRegistry().register(FakeTool.readOnlyReturning("LogQuery", "3 errors"));
        DiagnoseEngine engine = new DefaultDiagnoseEngine(llm, tools);

        engine.runStream(request(List.of()), lines::add, exitCode::set);

        assertThat(exitCode).hasValue(0);
        assertThat(hasEventType("content_block_start")).isTrue();
        JsonNode user = firstOfType("user");
        assertThat(user.get("message").get("content").get(0).get("content").asText()).isEqualTo("3 errors");
    }

    @Test
    void stopCancelsRunningSession() throws Exception {
        ControllableLlmClient llm = new ControllableLlmClient();
        DiagnoseEngine engine = new DefaultDiagnoseEngine(llm, new ToolRegistry());

        CompletableFuture<Void> run = CompletableFuture.runAsync(
                () -> engine.runStream(request(List.of()), lines::add, exitCode::set), runner);

        assertThat(llm.entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(engine.isRunning("s-1")).isTrue();
        engine.stop("s-1");
        run.get(3, TimeUnit.SECONDS);

        assertThat(exitCode).hasValue(-1);
        assertThat(llm.calls).hasValue(1);
        assertThat(engine.isRunning("s-1")).isFalse();
    }

    @Test
    void isRunningReflectsLifecycle() throws Exception {
        ControllableLlmClient llm = new ControllableLlmClient();
        DiagnoseEngine engine = new DefaultDiagnoseEngine(llm, new ToolRegistry());

        assertThat(engine.isRunning("s-1")).isFalse();
        CompletableFuture<Void> run = CompletableFuture.runAsync(
                () -> engine.runStream(request(List.of()), lines::add, exitCode::set), runner);

        assertThat(llm.entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(engine.isRunning("s-1")).isTrue();
        llm.release.countDown();
        run.get(3, TimeUnit.SECONDS);

        assertThat(engine.isRunning("s-1")).isFalse();
        assertThat(exitCode).hasValue(0);
    }

    private RunRequest request(List<TurnMessage> history) {
        return RunRequest.builder()
                .workingDir(System.getProperty("user.dir"))
                .userMessage("hi")
                .sessionId("s-1")
                .timeoutSeconds(0)
                .history(history)
                .build();
    }

    private List<String> topTypes() {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            out.add(parse(l).path("type").asText());
        }
        return out;
    }

    private String firstTextDelta() {
        return lines.stream().map(this::parse)
                .filter(n -> n.path("event").path("delta").path("type").asText().equals("text_delta"))
                .map(n -> n.get("event").get("delta").get("text").asText())
                .findFirst().orElseThrow();
    }

    private boolean hasEventType(String eventType) {
        return lines.stream().map(this::parse)
                .anyMatch(n -> n.path("event").path("type").asText().equals(eventType));
    }

    private JsonNode firstOfType(String type) {
        return lines.stream().map(this::parse)
                .filter(n -> n.path("type").asText().equals(type))
                .findFirst().orElseThrow();
    }

    private JsonNode parse(String line) {
        try {
            return mapper.readTree(line);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Hand-written blocking double: streams dots until cancelled or released. */
    private static final class ControllableLlmClient implements LlmClient {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void streamChat(ChatRequest request, StreamHandler handler) {
            calls.incrementAndGet();
            entered.countDown();
            for (int i = 0; i < 2000 && release.getCount() > 0; i++) {
                handler.onPartialText(".");
                sleepQuietly();
            }
            handler.onComplete(AiMessage.text("done"));
        }

        private static void sleepQuietly() {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
