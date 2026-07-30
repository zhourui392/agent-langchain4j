package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.diagnosis.OperationalContext;

import java.util.List;
import java.util.Objects;

/**
 * One diagnosis request from the host. Built fluently so the engine never
 * exposes a wide constructor (the ≤5-param rule). The engine is stateless:
 * {@code history} carries every prior turn the host wants the model to see.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class RunRequest {

    private final String workingDir;
    private final String userMessage;
    private final String sessionId;
    private final String env;
    private final long timeoutSeconds;
    private final List<TurnMessage> history;
    private final String stateSnapshot;
    private final OperationalContext operationalContext;

    private RunRequest(Builder builder) {
        this.workingDir = builder.workingDir;
        this.userMessage = builder.userMessage;
        this.sessionId = builder.sessionId;
        this.operationalContext = resolveContext(builder);
        this.env = resolveEnv(builder.env, operationalContext);
        this.timeoutSeconds = builder.timeoutSeconds;
        this.history = List.copyOf(builder.history);
        this.stateSnapshot = builder.stateSnapshot;
    }

    public String workingDir() {
        return workingDir;
    }

    public String userMessage() {
        return userMessage;
    }

    public String sessionId() {
        return sessionId;
    }

    /** @deprecated use {@link #operationalContext()} for typed host facts. */
    @Deprecated
    public String env() {
        return env;
    }

    public OperationalContext operationalContext() {
        return operationalContext;
    }

    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    public List<TurnMessage> history() {
        return history;
    }

    public String stateSnapshot() {
        return stateSnapshot;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String workingDir;
        private String userMessage;
        private String sessionId;
        private String env;
        private long timeoutSeconds;
        private List<TurnMessage> history = List.of();
        private String stateSnapshot = "";
        private OperationalContext operationalContext;

        public Builder workingDir(String workingDir) {
            this.workingDir = workingDir;
            return this;
        }

        public Builder userMessage(String userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder env(String env) {
            this.env = env;
            return this;
        }

        public Builder operationalContext(OperationalContext operationalContext) {
            this.operationalContext = Objects.requireNonNull(
                    operationalContext, "operationalContext");
            return this;
        }

        public Builder timeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder history(List<TurnMessage> history) {
            this.history = history == null ? List.of() : history;
            return this;
        }

        public Builder stateSnapshot(String stateSnapshot) {
            this.stateSnapshot = stateSnapshot == null ? "" : stateSnapshot;
            return this;
        }

        public RunRequest build() {
            Objects.requireNonNull(workingDir, "workingDir");
            Objects.requireNonNull(userMessage, "userMessage");
            Objects.requireNonNull(sessionId, "sessionId");
            return new RunRequest(this);
        }
    }

    private static OperationalContext resolveContext(Builder builder) {
        OperationalContext context = builder.operationalContext == null
                ? OperationalContext.legacy(builder.env) : builder.operationalContext;
        if (hasText(builder.env) && context.hasKnownEnvironment()
                && !builder.env.trim().equals(context.environment().name())) {
            throw new IllegalArgumentException("env conflicts with operationalContext.environment");
        }
        return context;
    }

    private static String resolveEnv(String env, OperationalContext context) {
        if (hasText(env)) {
            return env.trim();
        }
        return context.hasKnownEnvironment() ? context.environment().name() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
