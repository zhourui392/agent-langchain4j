package com.anthropic.cclc.application;

import com.anthropic.cclc.domain.context.ContextProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class SystemPromptComposer {

    static final String DYNAMIC_MARKER = "\n\n--- session context ---\n";

    private final String systemInstructions;
    private final List<ContextProvider> providers;

    public SystemPromptComposer(String systemInstructions, List<ContextProvider> providers) {
        this.systemInstructions = Objects.requireNonNull(systemInstructions, "systemInstructions");
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    }

    public SystemPrompt compose(Path workingDirectory) {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        String stablePrefix = renderSection(workingDirectory, false, systemInstructions);
        String dynamicSuffix = renderSection(workingDirectory, true, "");
        return new SystemPrompt(stablePrefix, dynamicSuffix);
    }

    private String renderSection(Path workingDirectory, boolean dynamic, String headerText) {
        StringBuilder sb = new StringBuilder();
        if (!headerText.isEmpty()) {
            sb.append(headerText);
        }
        for (ContextProvider provider : providers) {
            if (provider.isDynamic() != dynamic) {
                continue;
            }
            provider.provide(workingDirectory).ifPresent(text -> {
                sb.append("\n\n## ").append(provider.key()).append('\n').append(text);
            });
        }
        return sb.toString();
    }

    public record SystemPrompt(String stablePrefix, String dynamicSuffix) {

        public SystemPrompt {
            Objects.requireNonNull(stablePrefix, "stablePrefix");
            Objects.requireNonNull(dynamicSuffix, "dynamicSuffix");
        }

        public String full() {
            return dynamicSuffix.isEmpty()
                    ? stablePrefix
                    : stablePrefix + DYNAMIC_MARKER + dynamicSuffix;
        }
    }
}
