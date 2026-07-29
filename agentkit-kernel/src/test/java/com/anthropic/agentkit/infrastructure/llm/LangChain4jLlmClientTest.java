package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.agent.ModelIdentity;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.ContextWindowExceededException;
import com.anthropic.agentkit.domain.port.LlmClient.StreamHandler;
import com.anthropic.agentkit.domain.port.ProviderFailureException;
import com.anthropic.agentkit.domain.port.ProviderFailureKind;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jLlmClientTest {

    private final ChatRequest baseRequest = ChatRequest.builder()
            .message(UserMessage.of("hi"))
            .build();

    @Test
    void forwardsPartialTokensToHandler() {
        FakeStreamingChatModel fake = new FakeStreamingChatModel()
                .tokens("hel", "lo")
                .completionText("hello");
        LangChain4jLlmClient client = new LangChain4jLlmClient(fake);

        List<String> partials = new ArrayList<>();
        AtomicReference<AiMessage> completed = new AtomicReference<>();
        client.streamChat(baseRequest, new StreamHandler() {
            @Override public void onPartialText(String delta) { partials.add(delta); }
            @Override public void onComplete(AiMessage message) { completed.set(message); }
        });

        assertThat(partials).containsExactly("hel", "lo");
        assertThat(completed.get().text()).isEqualTo("hello");
    }

    @Test
    void assemblesCompleteAiMessageWithToolRequests() {
        FakeStreamingChatModel fake = new FakeStreamingChatModel()
                .completionText("calling bash")
                .toolRequest("u1", "Bash", "{\"command\":\"ls\"}");
        LangChain4jLlmClient client = new LangChain4jLlmClient(fake);

        AtomicReference<AiMessage> completed = new AtomicReference<>();
        client.streamChat(baseRequest, new StreamHandler() {
            @Override public void onPartialText(String delta) {}
            @Override public void onComplete(AiMessage message) { completed.set(message); }
        });

        AiMessage ai = completed.get();
        assertThat(ai.text()).isEqualTo("calling bash");
        assertThat(ai.toolUseRequests()).hasSize(1);
        assertThat(ai.toolUseRequests().get(0).id().value()).isEqualTo("u1");
        assertThat(ai.toolUseRequests().get(0).toolName()).isEqualTo("Bash");
        assertThat(ai.toolUseRequests().get(0).argumentsJson()).isEqualTo("{\"command\":\"ls\"}");
    }

    @Test
    void propagatesErrorToHandler() {
        FakeStreamingChatModel fake = new FakeStreamingChatModel()
                .failure(new RuntimeException("boom"));
        LangChain4jLlmClient client = new LangChain4jLlmClient(fake);

        AtomicReference<Throwable> received = new AtomicReference<>();
        client.streamChat(baseRequest, new StreamHandler() {
            @Override public void onPartialText(String delta) {}
            @Override public void onError(Throwable error) { received.set(error); }
        });

        assertThat(received.get()).hasMessage("boom");
    }

    @Test
    void mapsProviderContextOverflowToPortSignal() {
        FakeStreamingChatModel fake = new FakeStreamingChatModel()
                .failure(new RuntimeException("context_length_exceeded: prompt is too large"));
        LangChain4jLlmClient client = new LangChain4jLlmClient(fake);
        AtomicReference<Throwable> received = new AtomicReference<>();

        client.streamChat(baseRequest, new StreamHandler() {
            @Override public void onPartialText(String delta) { }
            @Override public void onError(Throwable error) { received.set(error); }
        });

        assertThat(received.get()).isInstanceOf(ContextWindowExceededException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void completionAndErrorCanOnlyWinOnce() {
        ControllableStreamingChatModel model = new ControllableStreamingChatModel();
        LangChain4jLlmClient client = new LangChain4jLlmClient(model);
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        var call = client.streamChat(baseRequest, new StreamHandler() {
            @Override public void onPartialText(String delta) { }
            @Override public void onComplete(AiMessage message) { completions.incrementAndGet(); }
            @Override public void onError(Throwable error) { errors.incrementAndGet(); }
        });
        model.complete("first");
        model.fail(new IllegalStateException("late"));

        assertThat(call.completion().toCompletableFuture().join().text()).isEqualTo("first");
        assertThat(completions).hasValue(1);
        assertThat(errors).hasValue(0);
    }

    @Test
    void cancellationIgnoresLateProviderCallbacks() {
        ControllableStreamingChatModel model = new ControllableStreamingChatModel();
        LangChain4jLlmClient client = new LangChain4jLlmClient(model);
        AtomicInteger callbacks = new AtomicInteger();

        var call = client.streamChat(baseRequest, new StreamHandler() {
            @Override public void onPartialText(String delta) { callbacks.incrementAndGet(); }
            @Override public void onComplete(AiMessage message) { callbacks.incrementAndGet(); }
            @Override public void onError(Throwable error) { callbacks.incrementAndGet(); }
        });
        assertThat(call.cancel()).isTrue();
        model.token("late");
        model.complete("late");
        model.fail(new IllegalStateException("later"));

        assertThat(call.completion().toCompletableFuture()).isCancelled();
        assertThat(callbacks).hasValue(0);
    }

    @Test
    void surfacesCacheReadTokensFromAnthropicUsage() {
        FakeStreamingChatModel fake = new FakeStreamingChatModel()
                .completionText("ok")
                .usage(AnthropicTokenUsage.builder()
                        .inputTokenCount(9)
                        .outputTokenCount(4)
                        .cacheReadInputTokens(6)
                        .build());
        LangChain4jLlmClient client = new LangChain4jLlmClient(fake);
        AtomicReference<List<Integer>> usage = new AtomicReference<>();

        client.streamChat(baseRequest, new StreamHandler() {
            @Override public void onPartialText(String delta) {}

            @Override
            public void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
                usage.set(List.of(inputTokens, outputTokens, cacheReadInputTokens));
            }
        });

        assertThat(usage.get()).containsExactly(9, 4, 6);
    }

    @Test
    void passesSystemPromptAsLeadingMessage() {
        FakeStreamingChatModel fake = new FakeStreamingChatModel().completionText("ok");
        LangChain4jLlmClient client = new LangChain4jLlmClient(fake);

        ChatRequest withSystem = ChatRequest.builder()
                .systemPrompt("you are helpful")
                .message(UserMessage.of("hi"))
                .build();
        client.streamChat(withSystem, new StreamHandler() {
            @Override public void onPartialText(String delta) {}
        });
        // smoke: did not throw
    }

    @Test
    void forwardsDomainToolSpecsAsLangChain4jToolSpecifications() {
        FakeStreamingChatModel fake = new FakeStreamingChatModel().completionText("ok");
        LangChain4jLlmClient client = new LangChain4jLlmClient(fake);

        String bashSchema = "{\"type\":\"object\",\"properties\":{" +
                "\"command\":{\"type\":\"string\",\"description\":\"shell command\"}," +
                "\"timeout\":{\"type\":\"integer\",\"description\":\"timeout in ms\"}" +
                "},\"required\":[\"command\"]}";
        String globSchema = "{\"type\":\"object\",\"properties\":{" +
                "\"pattern\":{\"type\":\"string\"}," +
                "\"respectGitignore\":{\"type\":\"boolean\"}" +
                "},\"required\":[\"pattern\"]}";

        ChatRequest withTools = ChatRequest.builder()
                .message(UserMessage.of("hi"))
                .tool(new com.anthropic.agentkit.domain.port.ToolSpec("Bash", "Execute a shell command", bashSchema))
                .tool(new com.anthropic.agentkit.domain.port.ToolSpec("Glob", "List files matching a glob", globSchema))
                .build();

        client.streamChat(withTools, new StreamHandler() {
            @Override public void onPartialText(String delta) {}
        });

        java.util.List<dev.langchain4j.agent.tool.ToolSpecification> lcSpecs =
                fake.capturedRequests().get(0).toolSpecifications();
        assertThat(lcSpecs).extracting(dev.langchain4j.agent.tool.ToolSpecification::name)
                .containsExactly("Bash", "Glob");
        assertThat(lcSpecs).extracting(dev.langchain4j.agent.tool.ToolSpecification::description)
                .containsExactly("Execute a shell command", "List files matching a glob");

        dev.langchain4j.model.chat.request.json.JsonObjectSchema bashParams = lcSpecs.get(0).parameters();
        assertThat(bashParams.properties().keySet()).containsExactlyInAnyOrder("command", "timeout");
        assertThat(bashParams.required()).containsExactly("command");

        dev.langchain4j.model.chat.request.json.JsonObjectSchema globParams = lcSpecs.get(1).parameters();
        assertThat(globParams.properties().keySet()).containsExactlyInAnyOrder("pattern", "respectGitignore");
        assertThat(globParams.required()).containsExactly("pattern");
    }

    @Test
    void emptyInputSchemaProducesEmptyJsonObjectSchema() {
        FakeStreamingChatModel fake = new FakeStreamingChatModel().completionText("ok");
        LangChain4jLlmClient client = new LangChain4jLlmClient(fake);

        ChatRequest withTools = ChatRequest.builder()
                .message(UserMessage.of("hi"))
                .tool(new com.anthropic.agentkit.domain.port.ToolSpec("Noop", "no inputs", "{}"))
                .build();

        client.streamChat(withTools, new StreamHandler() {
            @Override public void onPartialText(String delta) {}
        });

        dev.langchain4j.agent.tool.ToolSpecification spec = fake.capturedRequests().get(0).toolSpecifications().get(0);
        assertThat(spec.parameters().properties()).isEmpty();
        assertThat(spec.parameters().required()).isEmpty();
    }

    @Test
    void nestedMcpSchemaPreservesArrayItemsAndObjectProperties() {
        FakeStreamingChatModel fake = new FakeStreamingChatModel().completionText("ok");
        LangChain4jLlmClient client = new LangChain4jLlmClient(fake);
        String schema = """
                {"type":"object","properties":{
                  "names":{"type":"array","items":{"type":"string"}},
                  "filter":{"type":"object","properties":{"limit":{"type":"integer"}}}},
                 "required":["names"],"additionalProperties":false}
                """;
        ChatRequest request = ChatRequest.builder().message(UserMessage.of("hi"))
                .tool(new com.anthropic.agentkit.domain.port.ToolSpec(
                        "server.__discover_tools", "discover", schema)).build();

        client.streamChat(request, new StreamHandler() {
            @Override public void onPartialText(String delta) { }
        });

        var parameters = fake.capturedRequests().getFirst()
                .toolSpecifications().getFirst().parameters();
        assertThat(parameters.properties().get("names"))
                .isInstanceOf(dev.langchain4j.model.chat.request.json.JsonArraySchema.class);
        var names = (dev.langchain4j.model.chat.request.json.JsonArraySchema)
                parameters.properties().get("names");
        assertThat(names.items())
                .isInstanceOf(dev.langchain4j.model.chat.request.json.JsonStringSchema.class);
        assertThat(parameters.properties().get("filter"))
                .isInstanceOf(dev.langchain4j.model.chat.request.json.JsonObjectSchema.class);
        assertThat(parameters.additionalProperties()).isFalse();
    }

    @Test
    void rejectsUnsupportedToolSchemaBeforeCallingProvider() {
        FakeStreamingChatModel fake = new FakeStreamingChatModel().completionText("unused");
        LangChain4jLlmClient client = new LangChain4jLlmClient(fake);
        ChatRequest request = ChatRequest.builder().message(UserMessage.of("hi"))
                .tool(new com.anthropic.agentkit.domain.port.ToolSpec(
                        "Unsupported", "unsupported schema",
                        "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"null\"}}}"))
                .build();

        assertThatThrownBy(() -> client.streamChat(request, StreamHandler.noop()))
                .isInstanceOfSatisfying(ProviderFailureException.class,
                        failure -> assertThat(failure.kind())
                                .isEqualTo(ProviderFailureKind.SCHEMA_INCOMPATIBLE));
        assertThat(fake.capturedRequests()).isEmpty();
    }

    @Test
    void exposesConfiguredProviderNeutralModelIdentity() {
        ModelIdentity identity = new ModelIdentity("anthropic", "claude-test");
        LangChain4jLlmClient client = new LangChain4jLlmClient(
                new FakeStreamingChatModel(), identity);

        assertThat(client.modelIdentity()).isEqualTo(identity);
    }

    private static final class ControllableStreamingChatModel implements StreamingChatModel {
        private StreamingChatResponseHandler handler;

        @Override
        public void chat(dev.langchain4j.model.chat.request.ChatRequest request,
                         StreamingChatResponseHandler handler) {
            this.handler = handler;
        }

        private void token(String token) {
            handler.onPartialResponse(token);
        }

        private void complete(String text) {
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from(text))
                    .build());
        }

        private void fail(Throwable failure) {
            handler.onError(failure);
        }
    }
}
