package com.anthropic.cclc.infrastructure.llm;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.port.ChatRequest;
import com.anthropic.cclc.domain.port.LlmClient.StreamHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
                .tool(new com.anthropic.cclc.domain.port.ToolSpec("Bash", "Execute a shell command", bashSchema))
                .tool(new com.anthropic.cclc.domain.port.ToolSpec("Glob", "List files matching a glob", globSchema))
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
                .tool(new com.anthropic.cclc.domain.port.ToolSpec("Noop", "no inputs", "{}"))
                .build();

        client.streamChat(withTools, new StreamHandler() {
            @Override public void onPartialText(String delta) {}
        });

        dev.langchain4j.agent.tool.ToolSpecification spec = fake.capturedRequests().get(0).toolSpecifications().get(0);
        assertThat(spec.parameters().properties()).isEmpty();
        assertThat(spec.parameters().required()).isEmpty();
    }
}
